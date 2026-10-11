package com.ahmadre.hinata.article;

import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A person's favourite pages and the pages they opened last. Both lists only ever
 * name pages the person reads right now: a page that moved out of their reach
 * drops out of the answer, though its row stays until it is overwritten or pruned.
 */
@Component
@RequiredArgsConstructor
public class ArticleReading {

	/** Pages kept in the recently-opened list. */
	static final int RECENT = 30;

	/** Favourites one person keeps; far more than a list on a start page shows. */
	static final int MAX_STARRED = 200;

	private static final String USER_ID = "userId";
	private static final String ARTICLE_ID = "articleId";
	private static final String STARRED_AT = "starredAt";
	private static final String VIEWED_AT = "viewedAt";

	private final MongoTemplate mongo;
	private final ArticleAccess access;

	/** A page and when it was starred or opened. */
	public record Mark(String id, Instant at) {
	}

	/** The person's favourites and recently opened pages, newest first. */
	public record Marks(List<Mark> starred, List<Mark> recent) {
	}

	public Marks of(User user) {
		List<ArticleReaderState> starred = rows(user, STARRED_AT, MAX_STARRED);
		List<ArticleReaderState> recent = rows(user, VIEWED_AT, RECENT);
		Set<String> readable = readable(user, java.util.stream.Stream.concat(starred.stream(), recent.stream())
				.map(ArticleReaderState::getArticleId).collect(Collectors.toSet()));
		return new Marks(
				starred.stream().filter(r -> readable.contains(r.getArticleId()))
						.map(r -> new Mark(r.getArticleId(), r.getStarredAt())).toList(),
				recent.stream().filter(r -> readable.contains(r.getArticleId()))
						.map(r -> new Mark(r.getArticleId(), r.getViewedAt())).toList());
	}

	/** Marks or unmarks a page the caller reads as a favourite. */
	public void star(Article page, User user, boolean starred) {
		if (starred) {
			upsert(page, user, new Update().set(STARRED_AT, Instant.now()));
		}
		else {
			mongo.updateFirst(one(page, user), new Update().unset(STARRED_AT), ArticleReaderState.class);
			dropEmpty(user);
		}
	}

	/** Records that the caller opened a page they read, keeping the recent list short. */
	public void viewed(Article page, User user) {
		upsert(page, user, new Update().set(VIEWED_AT, Instant.now()));
		Query older = Query.query(Criteria.where(USER_ID).is(user.getId()).and(VIEWED_AT).ne(null))
				.with(Sort.by(Sort.Direction.DESC, VIEWED_AT)).skip(RECENT).limit(RECENT);
		older.fields().include("_id");
		List<Object> ids = mongo.find(older, org.bson.Document.class, "article_reader_state").stream()
				.map(row -> row.get("_id")).toList();
		if (ids.isEmpty()) return;
		mongo.updateMulti(Query.query(Criteria.where("_id").in(ids)), new Update().unset(VIEWED_AT),
				ArticleReaderState.class);
		dropEmpty(user);
	}

	/** The rows of deleted pages go with them. */
	void deleteOf(Collection<String> articleIds) {
		if (articleIds.isEmpty()) return;
		mongo.remove(Query.query(Criteria.where(ARTICLE_ID).in(articleIds)), ArticleReaderState.class);
	}

	/** Everything stored about one person, for their export. */
	public List<ArticleReaderState> allOf(String userId) {
		return mongo.find(Query.query(Criteria.where(USER_ID).is(userId)), ArticleReaderState.class);
	}

	public long deleteAllOf(String userId) {
		return mongo.remove(Query.query(Criteria.where(USER_ID).is(userId)), ArticleReaderState.class)
				.getDeletedCount();
	}

	private List<ArticleReaderState> rows(User user, String field, int limit) {
		return mongo.find(Query.query(Criteria.where(USER_ID).is(user.getId()).and(field).ne(null))
				.with(Sort.by(Sort.Direction.DESC, field)).limit(limit), ArticleReaderState.class);
	}

	private Set<String> readable(User user, Set<String> ids) {
		if (ids.isEmpty()) return Set.of();
		Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").in(ids),
				access.sightOf(user).criteria()));
		query.fields().include("_id");
		return mongo.find(query, org.bson.Document.class, "articles").stream()
				.map(com.ahmadre.hinata.common.MongoIds::of).collect(Collectors.toSet());
	}

	private void upsert(Article page, User user, Update update) {
		mongo.findAndModify(one(page, user), update.setOnInsert(USER_ID, user.getId())
				.setOnInsert(ARTICLE_ID, page.getId()), FindAndModifyOptions.options().upsert(true),
				ArticleReaderState.class);
	}

	private static Query one(Article page, User user) {
		return Query.query(Criteria.where(USER_ID).is(user.getId()).and(ARTICLE_ID).is(page.getId()));
	}

	/** A row that is neither a favourite nor recent says nothing any more. */
	private void dropEmpty(User user) {
		mongo.remove(Query.query(Criteria.where(USER_ID).is(user.getId()).and(STARRED_AT).is(null)
				.and(VIEWED_AT).is(null)), ArticleReaderState.class);
	}
}
