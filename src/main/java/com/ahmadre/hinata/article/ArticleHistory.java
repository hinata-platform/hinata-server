package com.ahmadre.hinata.article;

import com.ahmadre.hinata.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The saved states of a page. Every save that changes the title or the text
 * becomes a numbered version; saving without a change, or only moving the page,
 * adds none.
 *
 * <p>Pages from before versions existed have no history. Their first edit records
 * the state it replaces as version 1 — by the page's author, at its last change —
 * before the new state, so restoring "what was there before my edit" works on the
 * first edit too.
 */
@Component
@RequiredArgsConstructor
public class ArticleHistory {

	/** Versions kept per page; older ones are dropped as new ones arrive. */
	static final int KEPT = 100;

	/** Largest page of the version list. */
	static final int MAX_PAGE_SIZE = 50;

	private static final String ARTICLE_ID = "articleId";
	private static final String NUMBER = "number";

	private final MongoTemplate mongo;

	/** A version as the list shows it: no document. */
	public record Summary(int number, String title, String editorId, Instant createdAt) {
	}

	/** The first state of a new page. */
	void created(Article page, String editorId) {
		append(page, editorId, 1);
	}

	/**
	 * Whether a save makes a new version: the title or the text differs. The one
	 * place this rule lives; the service asks it to decide who edited last.
	 */
	static boolean changed(Article before, Article after) {
		return !Objects.equals(before.getTitle(), after.getTitle())
				|| !Objects.equals(before.getContentDoc(), after.getContentDoc());
	}

	/**
	 * Records the state {@code after} if it differs from {@code before} in title or
	 * text. {@code before} is the page as stored before this save.
	 */
	void saved(Article before, Article after, String editorId) {
		if (!changed(before, after)) return;
		int latest = latestNumber(after.getId());
		if (latest == 0) {
			String previousEditor = before.getLastEditorId() != null ? before.getLastEditorId() : before.getAuthorId();
			mongo.insert(version(before, previousEditor, 1,
					before.getUpdatedAt() != null ? before.getUpdatedAt() : Instant.now()));
			latest = 1;
		}
		int number = latest + 1;
		try {
			append(after, editorId, number);
		}
		catch (org.springframework.dao.DuplicateKeyException raced) {
			// Two saves of one page at once: the other took this number, this one takes the next.
			number = latestNumber(after.getId()) + 1;
			append(after, editorId, number);
		}
		prune(after.getId(), number);
	}

	public Page<Summary> list(String articleId, int page, int size) {
		int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
		PageRequest request = PageRequest.of(Math.max(page, 0), bounded);
		Query query = Query.query(Criteria.where(ARTICLE_ID).is(articleId));
		long total = mongo.count(query, ArticleVersion.class);
		query.with(Sort.by(Sort.Direction.DESC, NUMBER)).with(request);
		query.fields().exclude("contentDoc");
		List<Summary> rows = mongo.find(query, ArticleVersion.class).stream()
				.map(v -> new Summary(v.getNumber(), v.getTitle(), v.getEditorId(), v.getCreatedAt()))
				.toList();
		return new PageImpl<>(rows, request, total);
	}

	public ArticleVersion get(String articleId, int number) {
		ArticleVersion found = mongo.findOne(
				Query.query(Criteria.where(ARTICLE_ID).is(articleId).and(NUMBER).is(number)), ArticleVersion.class);
		if (found == null) throw ApiException.notFound("articleVersion");
		return found;
	}

	/** Everybody who saved a version of the page, most recent first. */
	public List<String> editors(String articleId) {
		Query query = Query.query(Criteria.where(ARTICLE_ID).is(articleId))
				.with(Sort.by(Sort.Direction.DESC, NUMBER)).limit(KEPT);
		query.fields().include("editorId");
		return mongo.find(query, ArticleVersion.class).stream()
				.map(ArticleVersion::getEditorId).filter(Objects::nonNull).distinct().toList();
	}

	void deleteOf(Collection<String> articleIds) {
		if (articleIds.isEmpty()) return;
		mongo.remove(Query.query(Criteria.where(ARTICLE_ID).in(articleIds)), ArticleVersion.class);
	}

	private void append(Article page, String editorId, int number) {
		mongo.insert(version(page, editorId, number, Instant.now()));
	}

	private static ArticleVersion version(Article page, String editorId, int number, Instant at) {
		return ArticleVersion.builder().articleId(page.getId()).number(number).title(page.getTitle())
				.contentDoc(page.getContentDoc()).editorId(editorId).createdAt(at).build();
	}

	private int latestNumber(String articleId) {
		Query query = Query.query(Criteria.where(ARTICLE_ID).is(articleId))
				.with(Sort.by(Sort.Direction.DESC, NUMBER)).limit(1);
		query.fields().include(NUMBER);
		ArticleVersion latest = mongo.findOne(query, ArticleVersion.class);
		return latest == null ? 0 : latest.getNumber();
	}

	private void prune(String articleId, int latest) {
		if (latest <= KEPT) return;
		mongo.remove(Query.query(Criteria.where(ARTICLE_ID).is(articleId).and(NUMBER).lte(latest - KEPT)),
				ArticleVersion.class);
	}
}
