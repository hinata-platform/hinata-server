package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.MarkState;
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
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Comments as moderation reaches them (HIN-169).
 *
 * <p>Removing one writes the placeholder into the comment itself. A voice message gives its audio
 * to the quarantine and becomes a text comment that shows the placeholder, so an app that knows
 * nothing of moderation shows it too. Reactions stay. A removed comment can still be deleted, but
 * never edited again ({@link IssueService#editComment}).
 */
@Component
@RequiredArgsConstructor
class CommentContentSurface implements ContentSurface {

	/** The field a comment's mark sits under. */
	static final String FIELD = "text";

	/** The path a query checks a comment's mark by. */
	static final String MARK_STATE = "moderation." + FIELD + ".state";

	private final IssueCommentRepository comments;
	private final IssueRepository issues;
	private final IssueReach reach;
	private final RichTextService richText;
	private final MediaReferences media;
	private final Quarantine quarantine;
	private final StorageService storage;
	private final MongoTemplate mongo;
	private final CommentEvents commentEvents;

	/** Whether moderation removed [comment]. */
	static boolean isRemoved(IssueComment comment) {
		ContentMark mark = comment.getModeration() == null ? null : comment.getModeration().get(FIELD);
		return mark != null && mark.isRemoved();
	}

	@Override
	public ContentKind kind() {
		return ContentKind.COMMENT;
	}

	@Override
	public Set<RemovalScope> scopes() {
		return Set.of(RemovalScope.WHOLE);
	}

	@Override
	public Optional<ContentSnapshot> snapshot(TargetRef target, User viewer) {
		return find(target).filter(found -> reach.canSee(found.issue(), viewer)).map(this::snapshotOf);
	}

	@Override
	public Optional<ContentSnapshot> forModeration(TargetRef target) {
		return find(target).map(this::snapshotOf);
	}

	/** A comment with the issue it stands on. */
	private record Found(IssueComment comment, Issue issue) {
	}

	private Optional<Found> find(TargetRef target) {
		if (target == null || target.id() == null) {
			return Optional.empty();
		}
		return comments.findById(target.id())
				.filter(comment -> target.parentId() == null || target.parentId().equals(comment.getIssueId()))
				.flatMap(comment -> issues.findById(comment.getIssueId()).map(issue -> new Found(comment, issue)));
	}

	private static ContentRef refOf(IssueComment comment, Issue issue) {
		return new ContentRef(ContentKind.COMMENT, comment.getId(), comment.getIssueId(), issue.getProjectId());
	}

	private ContentSnapshot snapshotOf(Found found) {
		IssueComment comment = found.comment();
		Issue issue = found.issue();
		IssueComment.Voice voice = comment.getVoice();
		List<MediaRef> objects = new ArrayList<>();
		if (voice != null) {
			objects.add(voiceRef(voice));
		}
		MediaReferences.idsIn(comment.getTextDoc())
				.forEach(id -> objects.add(MediaRef.of(MediaReferences.objectKey(id))));
		return new ContentSnapshot(
				refOf(comment, issue),
				comment.getOrigin() == null ? ContentOrigin.MEMBER : comment.getOrigin(),
				null,
				Set.of(comment.getAuthorId()),
				comment.getAuthorId(),
				null,
				ContentSnapshot.clip(comment.getText(), ContentSnapshot.EXCERPT_MAX),
				ContentSnapshot.clip(issue.getTitle(), ContentSnapshot.CONTEXT_MAX),
				issue.getReadableId(),
				List.copyOf(objects),
				comment.getCreatedAt(),
				comment.getEditedAt(),
				ContentSnapshot.hashOf(comment.getTextDoc(), comment.getText(),
						voice == null ? null : voice.getObjectKey()));
	}

	private static MediaRef voiceRef(IssueComment.Voice voice) {
		return new MediaRef(voice.getObjectKey(), null, voice.getContentType(), voice.getSize());
	}

	@Override
	public Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders) {
		requireScope(scope);
		Found found = find(target).orElseThrow(() -> ApiException.notFound("comment"));
		IssueComment comment = found.comment();
		ContentRef ref = refOf(comment, found.issue());
		if (isRemoved(comment)) {
			return Removal.nothing(ref, scope);
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("type", comment.resolvedType().name());
		putPresent(fields, "text", comment.getText());
		putPresent(fields, "textDoc", comment.getTextDoc());
		List<MediaRef> move = new ArrayList<>();
		if (comment.getVoice() != null) {
			fields.put("voice", voiceFields(comment.getVoice()));
			move.add(voiceRef(comment.getVoice()));
		}
		MediaReferences.Split images = media.split(MediaReferences.idsIn(comment.getTextDoc()),
				Map.of("issue_comments", Criteria.where("_id").is(comment.getId())), category.isSensitive());
		move.addAll(images.move());
		Quarantine.Held held = quarantine.hold(caseId, ref, scope, fields, move, images.copy());
		try {
			rewrite(comment, caseId, placeholders);
		}
		catch (RuntimeException ex) {
			quarantine.discard(held);
			throw ex;
		}
		List<String> pending = quarantine.deleteOriginals(held);
		dropThumbnails(images);
		commentEvents.publishChanged(comment.getIssueId());
		return new Removal(ref, scope, fields, held.media(), held.id(), pending, images.otherUses(),
				DeletedContent.sourceOf(comment, found.issue()));
	}

	private void rewrite(IssueComment comment, String caseId, Placeholders placeholders) {
		RichText placeholder = richText.placeholder(placeholders.removedText());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(comment.getId())),
				new Update()
						.set("type", IssueComment.Type.TEXT)
						.set("text", placeholder.text())
						.set("textDoc", placeholder.doc())
						.unset("voice")
						.set("moderation." + FIELD, ContentMark.removed(caseId)),
				IssueComment.class);
	}

	/** The previews of images that left: derived from the original, nothing to keep. */
	private void dropThumbnails(MediaReferences.Split images) {
		if (!storage.isConfigured()) {
			return;
		}
		images.move().forEach(image -> storage.delete(MediaReferences.thumbnailKeyOf(image.objectKey())));
	}

	@Override
	public RestoreOutcome restore(Removal removal) {
		IssueComment comment = comments.findById(removal.ref().id()).orElse(null);
		if (comment == null) {
			return RestoreOutcome.GONE;
		}
		if (!isRemoved(comment)) {
			return RestoreOutcome.REWRITTEN_SINCE;
		}
		if (removal.quarantineId() != null && !quarantine.restoreObjects(removal.quarantineId())) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		Map<String, Object> fields = removal.fields();
		Update update = new Update()
				.set("type", IssueComment.Type.valueOf(String.valueOf(fields.getOrDefault("type", "TEXT"))))
				.unset("moderation." + FIELD);
		setOrUnset(update, "text", fields.get("text"));
		setOrUnset(update, "textDoc", fields.get("textDoc"));
		if (fields.get("voice") instanceof Map<?, ?> voice) {
			update.set("voice", voiceOf(voice));
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(comment.getId())
				.and(MARK_STATE).is(MarkState.REMOVED.name())), update, IssueComment.class);
		commentEvents.publishChanged(comment.getIssueId());
		return RestoreOutcome.RESTORED;
	}

	private static void putPresent(Map<String, Object> fields, String name, Object value) {
		if (value != null) {
			fields.put(name, value);
		}
	}

	private static void setOrUnset(Update update, String name, Object value) {
		if (value == null) {
			update.unset(name);
		}
		else {
			update.set(name, value);
		}
	}

	private static Map<String, Object> voiceFields(IssueComment.Voice voice) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("objectKey", voice.getObjectKey());
		fields.put("durationMs", voice.getDurationMs());
		fields.put("peaks", voice.getPeaks() == null ? List.of() : List.copyOf(voice.getPeaks()));
		fields.put("size", voice.getSize());
		putPresent(fields, "contentType", voice.getContentType());
		return fields;
	}

	private static IssueComment.Voice voiceOf(Map<?, ?> fields) {
		List<Integer> peaks = new ArrayList<>();
		if (fields.get("peaks") instanceof List<?> list) {
			list.forEach(peak -> peaks.add(((Number) peak).intValue()));
		}
		return IssueComment.Voice.builder()
				.objectKey((String) fields.get("objectKey"))
				.durationMs(fields.get("durationMs") instanceof Number number ? number.intValue() : 0)
				.peaks(peaks)
				.size(fields.get("size") instanceof Number number ? number.longValue() : 0)
				.contentType((String) fields.get("contentType"))
				.build();
	}
}
