package com.ahmadre.hinata.search;

import com.ahmadre.hinata.common.MongoIds;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
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
import java.util.concurrent.TimeUnit;

/**
 * Comment text (HIN-165). A hit opens its issue scrolled to the comment.
 *
 * <p>A comment names its issue and not its project, so the caller's reach has to be brought to the
 * comments before anything is joined. First the issues the caller reaches are read, at most
 * {@link #REACH_IDS_MAX} of their ids:
 * <ul>
 * <li>A small reach (all of it within that bound) goes into the first {@code $match} as
 * {@code issueId $in}. Only the caller's own comments are read, on the {@code issueId} index,
 * however many comments the organisation holds; the join after the cap just names the issue.</li>
 * <li>A large reach is too many ids for a query. The comments are walked newest first on
 * {@code created_id}, at most {@link #SCAN_MAX} of them, each joined to its issue with the reach
 * inside the join, and only then capped. A comment on an issue the caller cannot open never
 * leaves the database. Past the scan bound a search finds nothing older, and the count is a lower
 * bound, which the palette shows as such.</li>
 * </ul>
 * Three reads whatever the collection holds: the reach, the aggregation and the authors' names.
 */
@Component
@RequiredArgsConstructor
class CommentSearch implements SearchSource {

	/** How much of a comment a hit shows. */
	static final int EXCERPT = 140;

	/** Most issue ids of the caller's reach that go into a query as {@code issueId $in}. */
	static final int REACH_IDS_MAX = 2000;

	/** Most comments a large reach walks before the join, newest first. */
	static final int SCAN_MAX = 5000;

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
		List<Document> pipeline = new ArrayList<>(visible(context, context.contains("text").getCriteriaObject(), cap));
		pipeline.add(new Document("$project", new Document("issueId", 1).append("authorId", 1).append("text", 1)
				.append("createdAt", 1).append("issue", 1)));
		List<Document> rows = new ArrayList<>();
		comments().aggregate(pipeline).maxTime(context.timeLeftMillis(), TimeUnit.MILLISECONDS).into(rows);
		Map<String, String> authors = authorNames(context, rows.stream().map(row -> row.getString("authorId"))
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

	/** Text comments on issues the caller reaches, by the same reach as the hits, up to [limit]. */
	@Override
	public long count(SearchContext context, int limit) {
		List<Document> pipeline = new ArrayList<>(visible(context,
				new Document("type", new Document("$ne", IssueComment.Type.VOICE.name())), limit));
		pipeline.add(new Document("$count", "n"));
		Document counted = comments().aggregate(pipeline)
				.maxTime(context.timeLeftMillis(), TimeUnit.MILLISECONDS).first();
		return counted == null ? 0 : ((Number) counted.get("n")).longValue();
	}

	/**
	 * The comments matching [match] on issues the caller reaches, newest first, at most [limit],
	 * each with its issue's title and key: the reach in the first match when it is small, inside
	 * the join after a bounded walk when it is not.
	 */
	private List<Document> visible(SearchContext context, Document match, int limit) {
		List<String> reach = reachableIssueIds(context);
		Document sort = new Document("$sort", new Document("createdAt", -1).append("_id", -1));
		if (reach.size() <= REACH_IDS_MAX) {
			if (reach.isEmpty()) return List.of(new Document("$match", new Document("_id", null)));
			Document mine = new Document("$and", List.of(new Document("issueId", new Document("$in", reach)), match));
			return List.of(new Document("$match", mine), sort, new Document("$limit", limit), join(null));
		}
		return List.of(
				new Document("$match", match),
				sort,
				new Document("$limit", SCAN_MAX),
				join(context.issues(false).getCriteriaObject()),
				new Document("$match", new Document("issue.0", new Document("$exists", true))),
				new Document("$limit", limit));
	}

	/** Up to one past {@link #REACH_IDS_MAX} ids of the issues the caller reaches, as comments name them. */
	private List<String> reachableIssueIds(SearchContext context) {
		Query query = context.bounded(Query.query(context.issues(false)).limit(REACH_IDS_MAX + 1));
		query.fields().include("_id");
		return mongo.find(query, Document.class, mongo.getCollectionName(Issue.class)).stream()
				.map(MongoIds::of).toList();
	}

	/** Each comment's issue, its title and key, as {@code issue}; only within [issueReach] when given. */
	private Document join(Document issueReach) {
		List<Document> lookup = new ArrayList<>();
		lookup.add(new Document("$match", new Document("$expr", new Document("$eq", List.of("$_id", "$$issue")))));
		if (issueReach != null) lookup.add(new Document("$match", issueReach));
		lookup.add(new Document("$project", new Document("title", 1).append("readableId", 1)));
		return new Document("$lookup", new Document("from", mongo.getCollectionName(Issue.class))
				.append("let", new Document("issue", new Document("$convert", new Document("input", "$issueId")
						.append("to", "objectId").append("onError", "$issueId"))))
				.append("pipeline", lookup)
				.append("as", "issue"));
	}

	private com.mongodb.client.MongoCollection<Document> comments() {
		return mongo.getCollection(mongo.getCollectionName(IssueComment.class));
	}

	private Map<String, String> authorNames(SearchContext context, Set<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Query query = context.bounded(Query.query(Criteria.where("_id").in(ids)));
		query.fields().include("displayName", "username");
		Map<String, String> names = new HashMap<>();
		for (Document user : mongo.find(query, Document.class, mongo.getCollectionName(User.class))) {
			String name = user.getString("displayName");
			names.put(String.valueOf(user.get("_id")), name == null || name.isBlank() ? user.getString("username") : name);
		}
		return names;
	}
}
