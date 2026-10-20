package com.ahmadre.hinata.search;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Comment text (HIN-165). A hit opens its issue scrolled to the comment.
 *
 * <p>A comment names its issue and not its project, so the caller's reach is applied in the same
 * aggregation that finds the text: newest comments first, each joined to its issue on {@code _id}
 * with the issue reach ({@link SearchContext#issues}) inside the join, and only then capped. A
 * comment on an issue the caller cannot open never leaves the database, and the cap counts only
 * what may be shown. Two reads whatever the collection holds: the aggregation and the authors' names.
 */
@Component
@Order(10)
@RequiredArgsConstructor
class CommentSearch implements SearchSource {

	/** How much of a comment a hit shows. */
	static final int EXCERPT = 140;

	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.COMMENTS;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		Document issueReach = context.issues(false).getCriteriaObject();
		List<Document> pipeline = List.of(
				new Document("$match", new Document("text",
						new Document("$regex", Pattern.quote(context.query())).append("$options", "i"))),
				new Document("$sort", new Document("createdAt", -1).append("_id", -1)),
				new Document("$lookup", new Document("from", mongo.getCollectionName(Issue.class))
						.append("let", new Document("issue", new Document("$convert", new Document("input", "$issueId")
								.append("to", "objectId").append("onError", "$issueId"))))
						.append("pipeline", List.of(
								new Document("$match", new Document("$expr", new Document("$eq", List.of("$_id", "$$issue")))),
								new Document("$match", issueReach),
								new Document("$project", new Document("title", 1).append("readableId", 1))))
						.append("as", "issue")),
				new Document("$match", new Document("issue.0", new Document("$exists", true))),
				new Document("$limit", cap),
				new Document("$project", new Document("issueId", 1).append("authorId", 1).append("text", 1)
						.append("createdAt", 1).append("issue", 1)));
		List<Document> rows = new ArrayList<>();
		mongo.getCollection(mongo.getCollectionName(IssueComment.class)).aggregate(pipeline).into(rows);
		Map<String, String> authors = authorNames(rows.stream().map(row -> row.getString("authorId"))
				.filter(Objects::nonNull).collect(LinkedHashSet::new, Set::add, Set::addAll));
		return rows.stream().map(row -> {
			Document issue = row.getList("issue", Document.class).get(0);
			String issueId = row.getString("issueId");
			String id = String.valueOf(row.get("_id"));
			Date created = row.getDate("createdAt");
			return SearchHit.builder()
					.category(SearchCategory.COMMENTS.name())
					.id(id)
					.route("/issues/" + issueId + "?comment=" + id)
					.title(context.excerpt(row.getString("text"), EXCERPT))
					.subtitle(issue.getString("title"))
					.readableId(issue.getString("readableId"))
					.issueId(issueId)
					.authorName(authors.get(row.getString("authorId")))
					.createdAt(created == null ? null : created.toInstant())
					.build();
		}).toList();
	}

	private Map<String, String> authorNames(Set<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include("displayName", "username");
		Map<String, String> names = new HashMap<>();
		for (Document user : mongo.find(query, Document.class, mongo.getCollectionName(User.class))) {
			String name = user.getString("displayName");
			names.put(String.valueOf(user.get("_id")), name == null || name.isBlank() ? user.getString("username") : name);
		}
		return names;
	}
}
