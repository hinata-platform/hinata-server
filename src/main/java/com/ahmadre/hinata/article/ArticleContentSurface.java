package com.ahmadre.hinata.article;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.content.VersionCopy;
import com.ahmadre.hinata.media.MediaReferences;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Knowledge pages as moderation reaches them (HIN-169).
 *
 * <p>Every reader of a page may edit it, so vandalism on a page is usually one person's version on
 * top of everybody else's work. {@link RemovalScope#REVERT}, the default, therefore puts the page
 * back to the last version <em>before</em> the reported one and moves only the reported version and
 * those after it into the quarantine, recording the reset as a version of its own.
 * {@link RemovalScope#WHOLE} replaces title and text with the placeholder and moves every version.
 *
 * <p>A page's target may name the reported version as its parent, the version the snapshot saw;
 * without one the newest version counts as reported.
 */
@Component
@RequiredArgsConstructor
class ArticleContentSurface implements ContentSurface {

	static final String TITLE = "title";
	static final String CONTENT = "content";

	/** Versions read and moved per round, so a hundred large ones never sit in memory at once. */
	static final int BATCH = 10;

	private static final String ARTICLE_ID = "articleId";
	private static final String NUMBER = "number";
	private static final String RESET_VERSION = "resetVersion";

	private final ArticleRepository articles;
	private final ArticleAccess access;
	private final ArticleHistory history;
	private final RichTextService richText;
	private final MediaReferences media;
	private final Quarantine quarantine;
	private final StorageService storage;
	private final MongoTemplate mongo;

	/** Whether moderation removed [field] of [page] and nobody wrote it anew. */
	static boolean isRemoved(Article page, String field) {
		ContentMark mark = page.getModeration() == null ? null : page.getModeration().get(field);
		return mark != null && mark.isRemoved();
	}

	/** Turns the mark of a removed field that [after] writes anew into {@code REWRITTEN}. */
	static void markRewritten(Article before, Article after) {
		if (after.getModeration() == null) {
			return;
		}
		Map<String, ContentMark> marks = new LinkedHashMap<>(after.getModeration());
		boolean changed = false;
		if (!Objects.equals(before.getTitle(), after.getTitle()) && isRemoved(after, TITLE)) {
			marks.put(TITLE, marks.get(TITLE).rewritten());
			changed = true;
		}
		if (!Objects.equals(before.getContentDoc(), after.getContentDoc()) && isRemoved(after, CONTENT)) {
			marks.put(CONTENT, marks.get(CONTENT).rewritten());
			changed = true;
		}
		if (changed) {
			after.setModeration(marks);
		}
	}

	@Override
	public ContentKind kind() {
		return ContentKind.ARTICLE;
	}

	@Override
	public Set<RemovalScope> scopes() {
		return Set.of(RemovalScope.REVERT, RemovalScope.WHOLE);
	}

	@Override
	public Optional<ContentSnapshot> snapshot(TargetRef target, User viewer) {
		return find(target).filter(page -> access.canSee(page, viewer)).map(this::snapshotOf);
	}

	@Override
	public Optional<ContentSnapshot> forModeration(TargetRef target) {
		return find(target).map(this::snapshotOf);
	}

	private Optional<Article> find(TargetRef target) {
		return target == null || target.id() == null ? Optional.empty() : articles.findById(target.id());
	}

	private ContentRef refOf(Article page, Integer version) {
		return new ContentRef(ContentKind.ARTICLE, page.getId(), version == null ? null : String.valueOf(version),
				page.getProjectId());
	}

	private ContentSnapshot snapshotOf(Article page) {
		int latest = latestNumber(page.getId());
		Set<String> contributors = new LinkedHashSet<>(history.editors(page.getId()));
		if (page.getAuthorId() != null) {
			contributors.add(page.getAuthorId());
		}
		String lastEditor = page.getLastEditorId() != null ? page.getLastEditorId() : page.getAuthorId();
		return new ContentSnapshot(
				refOf(page, latest == 0 ? null : latest),
				ContentOrigin.MEMBER,
				null,
				contributors.stream().limit(ContentSnapshot.CONTRIBUTORS_MAX)
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
				lastEditor,
				page.getTitle(),
				ContentSnapshot.clip(page.getContent(), ContentSnapshot.EXCERPT_MAX),
				ContentSnapshot.clip(page.getSpace(), ContentSnapshot.CONTEXT_MAX),
				page.getTitle(),
				MediaReferences.idsIn(page.getContentDoc()).stream()
						.map(id -> MediaRef.of(MediaReferences.objectKey(id)))
						.toList(),
				page.getCreatedAt(),
				page.getUpdatedAt(),
				ContentSnapshot.hashOf(page.getTitle(), page.getContentDoc()),
				isRemoved(page, TITLE) && isRemoved(page, CONTENT));
	}

	@Override
	public Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders) {
		requireScope(scope);
		Article page = find(target).orElseThrow(() -> ApiException.notFound("article"));
		if (isRemoved(page, TITLE) && isRemoved(page, CONTENT)) {
			return Removal.nothing(refOf(page, null), scope);
		}
		int latest = latestNumber(page.getId());
		int reported = reportedVersion(target, latest);
		ArticleVersion before = scope == RemovalScope.REVERT && reported > 0 ? versionBefore(page.getId(), reported)
				: null;
		// Nothing before the reported version to go back to: the page goes as a whole.
		boolean revert = before != null;
		Criteria moved = Criteria.where(ARTICLE_ID).is(page.getId());
		if (revert) {
			moved = moved.and(NUMBER).gte(reported);
		}
		ContentRef ref = refOf(page, reported == 0 ? null : reported);

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put(TITLE, page.getTitle());
		putPresent(fields, CONTENT, page.getContent());
		putPresent(fields, "contentDoc", page.getContentDoc());
		fields.put("referencedIssueKeys", page.getReferencedIssueKeys() == null ? List.of()
				: List.copyOf(page.getReferencedIssueKeys()));
		int resetVersion = latest + 1;
		if (revert) {
			fields.put(RESET_VERSION, resetVersion);
		}

		Set<String> images = new LinkedHashSet<>(MediaReferences.idsIn(page.getContentDoc()));
		forEachVersion(moved, batch -> batch.forEach(version -> images.addAll(MediaReferences.idsIn(version.contentDoc()))));
		Map<String, Criteria> excluded = Map.of(
				"articles", Criteria.where("_id").is(page.getId()),
				"article_versions", moved);
		MediaReferences.Split split = media.split(images, excluded, category.isSensitive());

		RemovalScope taken = revert ? RemovalScope.REVERT : RemovalScope.WHOLE;
		Quarantine.Held held = quarantine.hold(caseId, ref, taken, fields, split.move(), split.copy());
		try {
			Quarantine.Held into = held;
			forEachVersion(moved, batch -> quarantine.keepVersions(into, batch));
			if (revert) {
				revertTo(page, before, resetVersion, placeholders);
			}
			else {
				replace(page, caseId, placeholders);
			}
		}
		catch (RuntimeException ex) {
			quarantine.discard(held);
			throw ex;
		}
		// The originals of the versions go last, like the objects: their copies are kept by now.
		Criteria originals = revert ? Criteria.where(ARTICLE_ID).is(page.getId()).and(NUMBER).gte(reported).lte(latest)
				: Criteria.where(ARTICLE_ID).is(page.getId());
		mongo.remove(Query.query(originals), ArticleVersion.class);
		List<String> pending = quarantine.deleteOriginals(held);
		dropThumbnails(split);
		return new Removal(ref, taken, fields, held.media(), held.id(), pending, split.otherUses(), null);
	}

	private static int reportedVersion(TargetRef target, int latest) {
		if (target.parentId() == null) {
			return latest;
		}
		try {
			return Integer.parseInt(target.parentId());
		}
		catch (NumberFormatException notANumber) {
			return latest;
		}
	}

	private ArticleVersion versionBefore(String articleId, int number) {
		return mongo.findOne(Query.query(Criteria.where(ARTICLE_ID).is(articleId).and(NUMBER).lt(number))
				.with(Sort.by(Sort.Direction.DESC, NUMBER)).limit(1), ArticleVersion.class);
	}

	/** The page as it was before the reported version, recorded as a version of moderation's own. */
	private void revertTo(Article page, ArticleVersion before, int resetVersion, Placeholders placeholders) {
		RichText body = richText.fromLexical(before.getContentDoc());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(page.getId())), new Update()
						.set(TITLE, before.getTitle())
						.set(CONTENT, body.text())
						.set("contentDoc", body.doc())
						.set("referencedIssueKeys", new ArrayList<>(body.issueKeys()))
						.set("updatedAt", Instant.now()),
				Article.class);
		mongo.insert(ArticleVersion.builder()
				.articleId(page.getId())
				.number(resetVersion)
				.title(before.getTitle())
				.contentDoc(before.getContentDoc())
				.note(placeholders.revertNote())
				.createdAt(Instant.now())
				.build());
	}

	private void replace(Article page, String caseId, Placeholders placeholders) {
		RichText placeholder = richText.placeholder(placeholders.removedText());
		ContentMark mark = ContentMark.removed(caseId);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(page.getId())), new Update()
						.set(TITLE, placeholders.removedTitle())
						.set(CONTENT, placeholder.text())
						.set("contentDoc", placeholder.doc())
						.set("referencedIssueKeys", List.of())
						.set("moderation." + TITLE, mark)
						.set("moderation." + CONTENT, mark),
				Article.class);
	}

	private void dropThumbnails(MediaReferences.Split images) {
		if (!storage.isConfigured()) {
			return;
		}
		images.move().forEach(image -> storage.delete(MediaReferences.thumbnailKeyOf(image.objectKey())));
	}

	@Override
	public RestoreOutcome restore(Removal removal) {
		Article page = articles.findById(removal.ref().id()).orElse(null);
		if (page == null) {
			return RestoreOutcome.GONE;
		}
		Map<String, Object> fields = removal.fields();
		boolean revert = removal.scope() == RemovalScope.REVERT;
		if (revert ? latestNumber(page.getId()) != ((Number) fields.get(RESET_VERSION)).intValue()
				: !(isRemoved(page, TITLE) && isRemoved(page, CONTENT))) {
			return RestoreOutcome.REWRITTEN_SINCE;
		}
		if (removal.quarantineId() != null && !quarantine.restoreObjects(removal.quarantineId())) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		if (revert) {
			mongo.remove(Query.query(Criteria.where(ARTICLE_ID).is(page.getId())
					.and(NUMBER).is(((Number) fields.get(RESET_VERSION)).intValue())), ArticleVersion.class);
		}
		if (removal.quarantineId() != null) {
			quarantine.forEachVersions(removal.quarantineId(), batch -> mongo.insertAll(batch.stream()
					.map(version -> ArticleVersion.builder()
							.articleId(page.getId())
							.number(version.number())
							.title(version.title())
							.contentDoc(version.contentDoc())
							.editorId(version.editorId())
							.createdAt(version.savedAt())
							.build())
					.toList()));
		}
		Update update = new Update()
				.set(TITLE, fields.get(TITLE))
				.set("referencedIssueKeys", fields.getOrDefault("referencedIssueKeys", List.of()))
				.unset("moderation." + TITLE)
				.unset("moderation." + CONTENT);
		setOrUnset(update, CONTENT, fields.get(CONTENT));
		setOrUnset(update, "contentDoc", fields.get("contentDoc"));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(page.getId())), update, Article.class);
		return RestoreOutcome.RESTORED;
	}

	private int latestNumber(String articleId) {
		Query query = Query.query(Criteria.where(ARTICLE_ID).is(articleId))
				.with(Sort.by(Sort.Direction.DESC, NUMBER)).limit(1);
		query.fields().include(NUMBER);
		ArticleVersion latest = mongo.findOne(query, ArticleVersion.class);
		return latest == null ? 0 : latest.getNumber();
	}

	/** Hands the versions [criteria] matches to [batch], oldest first, {@value #BATCH} at a time. */
	private void forEachVersion(Criteria criteria, Consumer<List<VersionCopy>> batch) {
		int after = Integer.MIN_VALUE;
		while (true) {
			List<ArticleVersion> page = mongo.find(Query.query(new Criteria().andOperator(criteria,
							Criteria.where(NUMBER).gt(after)))
					.with(Sort.by(NUMBER)).limit(BATCH), ArticleVersion.class);
			if (page.isEmpty()) {
				return;
			}
			batch.accept(page.stream()
					.map(version -> new VersionCopy(version.getNumber(), version.getTitle(), version.getContentDoc(),
							version.getEditorId(), version.getCreatedAt()))
					.toList());
			after = page.getLast().getNumber();
		}
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
}
