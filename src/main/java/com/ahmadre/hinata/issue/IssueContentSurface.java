package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.media.MediaReferences;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.ahmadre.hinata.content.RemovedFields.putPresent;
import static com.ahmadre.hinata.content.RemovedFields.setOrUnset;

/**
 * Issues as moderation reaches them (HIN-169): the title, the description, or both. Attachments
 * are content of their own ({@link AttachmentContentSurface}), comments too.
 *
 * <p>A removed field holds the placeholder and a mark. Whoever may edit the issue may write the
 * field anew; the mark then turns {@link com.ahmadre.hinata.content.MarkState#REWRITTEN} and the
 * field shows their text again ({@link #markRewritten}).
 */
@Component
@RequiredArgsConstructor
class IssueContentSurface implements ContentSurface {

	static final String TITLE = "title";
	static final String DESCRIPTION = "description";

	/** Most activity rows read to find who wrote the title and the description. */
	private static final int ACTIVITY_SCAN = 500;

	private final IssueRepository issues;
	private final IssueReach reach;
	private final RichTextService richText;
	private final MediaReferences media;
	private final Quarantine quarantine;
	private final MongoTemplate mongo;

	/** Whether moderation removed [field] of [issue] and nobody wrote it anew. */
	static boolean isRemoved(Issue issue, String field) {
		ContentMark mark = issue.getModeration() == null ? null : issue.getModeration().get(field);
		return mark != null && mark.isRemoved();
	}

	/**
	 * Turns the mark of a removed field that [after] writes anew into {@code REWRITTEN}, so it shows
	 * the new text. Called by every save of an edit, before it is written.
	 */
	static void markRewritten(Issue before, Issue after) {
		if (after.getModeration() == null) {
			return;
		}
		rewritten(after, TITLE, !Objects.equals(before.getTitle(), after.getTitle()));
		rewritten(after, DESCRIPTION, !Objects.equals(before.getDescriptionDoc(), after.getDescriptionDoc()));
	}

	private static void rewritten(Issue issue, String field, boolean changed) {
		ContentMark mark = issue.getModeration().get(field);
		if (changed && mark != null && mark.isRemoved()) {
			Map<String, ContentMark> marks = new LinkedHashMap<>(issue.getModeration());
			marks.put(field, mark.rewritten());
			issue.setModeration(marks);
		}
	}

	@Override
	public ContentKind kind() {
		return ContentKind.ISSUE;
	}

	@Override
	public Set<RemovalScope> scopes() {
		return Set.of(RemovalScope.TITLE, RemovalScope.DESCRIPTION, RemovalScope.WHOLE);
	}

	@Override
	public Optional<ContentSnapshot> snapshot(TargetRef target, User viewer) {
		return find(target).filter(issue -> reach.canSee(issue, viewer)).map(this::snapshotOf);
	}

	@Override
	public Optional<ContentSnapshot> forModeration(TargetRef target) {
		return find(target).map(this::snapshotOf);
	}

	/** By id, or by the readable key an address carries, as a report without an account names it (HIN-175). */
	private Optional<Issue> find(TargetRef target) {
		return target == null || target.id() == null ? Optional.empty()
				: issues.findById(target.id()).or(() -> issues.findByReadableIdIgnoreCase(target.id()));
	}

	private static ContentRef refOf(Issue issue) {
		return new ContentRef(ContentKind.ISSUE, issue.getId(), null, issue.getProjectId());
	}

	private ContentSnapshot snapshotOf(Issue issue) {
		boolean mailed = issue.getInboundMessageId() != null;
		List<String> writers = writersOf(issue);
		if (mailed) {
			// The account a mail was filed under did not necessarily write it: a sender can be
			// forged. Whoever edited it since did write their part.
			writers = writers.stream().filter(id -> !id.equals(issue.getReporterId())).toList();
		}
		return new ContentSnapshot(
				refOf(issue),
				mailed ? ContentOrigin.EMAIL : ContentOrigin.MEMBER,
				mailed ? issue.getReporterEmail() : null,
				new LinkedHashSet<>(writers),
				writers.isEmpty() ? (mailed ? null : issue.getReporterId()) : writers.getFirst(),
				issue.getTitle(),
				ContentSnapshot.clip(issue.getDescription(), ContentSnapshot.EXCERPT_MAX),
				null,
				issue.getReadableId(),
				MediaReferences.idsIn(issue.getDescriptionDoc()).stream()
						.map(id -> MediaRef.of(MediaReferences.objectKey(id)))
						.toList(),
				issue.getCreatedAt(),
				issue.getUpdatedAt(),
				ContentSnapshot.hashOf(issue.getTitle(), issue.getDescriptionDoc()),
				isRemoved(issue, TITLE) && isRemoved(issue, DESCRIPTION));
	}

	/**
	 * Everyone who wrote the title or the description, whoever changed them last first, and whoever
	 * created the issue: anybody with edit rights may change both, so the reported state can be
	 * several people's.
	 */
	private List<String> writersOf(Issue issue) {
		Query query = Query.query(Criteria.where("issueId").is(issue.getId())
						.and("field").in(IssueActivity.Field.TITLE, IssueActivity.Field.DESCRIPTION))
				.with(Sort.by(Sort.Direction.DESC, "createdAt"))
				.limit(ACTIVITY_SCAN);
		query.fields().include("actorId");
		Set<String> writers = new LinkedHashSet<>();
		for (IssueActivity activity : mongo.find(query, IssueActivity.class)) {
			if (activity.getActorId() != null) {
				writers.add(activity.getActorId());
			}
		}
		if (issue.getReporterId() != null) {
			writers.add(issue.getReporterId());
		}
		return writers.stream().limit(ContentSnapshot.CONTRIBUTORS_MAX).toList();
	}

	@Override
	public Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders) {
		requireScope(scope);
		Issue issue = find(target).orElseThrow(() -> ApiException.notFound("issue"));
		ContentRef ref = refOf(issue);
		boolean title = scope != RemovalScope.DESCRIPTION && !isRemoved(issue, TITLE);
		boolean description = scope != RemovalScope.TITLE && !isRemoved(issue, DESCRIPTION);
		if (!title && !description) {
			return Removal.nothing(ref, scope);
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (title) {
			fields.put(TITLE, issue.getTitle());
		}
		MediaReferences.Split images = MediaReferences.Split.NONE;
		if (description) {
			putPresent(fields, DESCRIPTION, issue.getDescription());
			putPresent(fields, "descriptionDoc", issue.getDescriptionDoc());
			images = media.split(MediaReferences.idsIn(issue.getDescriptionDoc()),
					Map.of("issues", Criteria.where("_id").is(issue.getId())), category.isSensitive());
		}
		Quarantine.Held held = quarantine.hold(caseId, ref, scope, fields, images.move(), images.copy());
		List<String> pending = quarantine.rewriteHeld(held,
				() -> rewrite(issue, title, description, caseId, placeholders));
		media.dropThumbnails(images);
		CopySource copies = new CopySource(ref, null, null, title ? issue.getTitle() : null,
				description ? issue.getDescriptionDoc() : null, keysOf(issue));
		return new Removal(ref, scope, fields, held.media(), held.id(), pending, images.otherUses(), copies);
	}

	private void rewrite(Issue issue, boolean title, boolean description, String caseId,
			Placeholders placeholders) {
		Update update = new Update();
		ContentMark mark = ContentMark.removed(caseId);
		if (title) {
			update.set(TITLE, placeholders.removedTitle()).set("moderation." + TITLE, mark);
		}
		if (description) {
			RichText placeholder = richText.placeholder(placeholders.removedText());
			update.set(DESCRIPTION, placeholder.text()).set("descriptionDoc", placeholder.doc())
					.set("moderation." + DESCRIPTION, mark);
		}
		Criteria criteria = Criteria.where("_id").is(issue.getId());
		mongo.updateFirst(Query.query(criteria), update, Issue.class);
		if (title) {
			IssueSearchText.refresh(mongo, criteria);
		}
	}

	@Override
	public RestoreOutcome restore(Removal removal) {
		Issue issue = issues.findById(removal.ref().id()).orElse(null);
		if (issue == null) {
			return RestoreOutcome.GONE;
		}
		Map<String, Object> fields = removal.fields();
		boolean title = fields.containsKey(TITLE);
		boolean description = fields.containsKey(DESCRIPTION) || fields.containsKey("descriptionDoc");
		if ((title && !isRemoved(issue, TITLE)) || (description && !isRemoved(issue, DESCRIPTION))) {
			return RestoreOutcome.REWRITTEN_SINCE;
		}
		if (removal.quarantineId() != null && !quarantine.restoreObjects(removal.quarantineId())) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		Update update = new Update();
		if (title) {
			update.set(TITLE, fields.get(TITLE)).unset("moderation." + TITLE);
		}
		if (description) {
			setOrUnset(update, DESCRIPTION, fields.get(DESCRIPTION));
			setOrUnset(update, "descriptionDoc", fields.get("descriptionDoc"));
			update.unset("moderation." + DESCRIPTION);
		}
		Criteria criteria = Criteria.where("_id").is(issue.getId());
		mongo.updateFirst(Query.query(criteria), update, Issue.class);
		if (title) {
			IssueSearchText.refresh(mongo, criteria);
		}
		return RestoreOutcome.RESTORED;
	}



	/** Kept for the issue keys a removed title's copies are found by. */
	static List<String> keysOf(Issue issue) {
		List<String> keys = new ArrayList<>();
		if (issue.getReadableId() != null) {
			keys.add(issue.getReadableId());
		}
		if (issue.getFormerReadableIds() != null) {
			keys.addAll(issue.getFormerReadableIds());
		}
		return keys;
	}
}
