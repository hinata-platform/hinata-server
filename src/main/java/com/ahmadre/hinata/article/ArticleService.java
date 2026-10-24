package com.ahmadre.hinata.article;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writing the knowledge base: where a page goes, and that its subtree goes with
 * it. Shared by the REST routes and the MCP tools, which used to differ exactly
 * here — the tool filed pages into any project or team without asking.
 *
 * <p>One invariant carries the rest: <b>a page lives where its parent lives.</b>
 * Creating under a parent, or moving under one, takes the parent's place, and
 * moving a top-level page takes its whole subtree along. That is what lets a
 * Team-Admin open a page "with everything under it" to somebody in one grant.
 */
@Service
@RequiredArgsConstructor
public class ArticleService {

	/** Hard ceiling on the array-shaped corpus load. */
	public static final int LIST_CAP = 1000;

	/** Deeper than any tree a person builds; bounds the walks below. */
	private static final int MAX_DEPTH = 50;

	private final ArticleRepository articles;
	private final ArticleAccess access;
	private final MongoTemplate mongo;
	private final com.ahmadre.hinata.project.ProjectService projects;
	private final com.ahmadre.hinata.team.TeamRepository teams;
	private final com.ahmadre.hinata.audit.AuditService audit;
	private final ArticleHistory history;
	private final ArticleReading reading;
	private final com.ahmadre.hinata.richtext.RichTextService richText;

	/** The page, or 404 — also when it exists but the caller may not read it. */
	public Article readable(String id, User user) {
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!access.canSee(article, user)) {
			// Don't leak existence of articles the user has no access to.
			throw ApiException.notFound("article");
		}
		return article;
	}

	/**
	 * The pages {@code user} reads, optionally narrowed to one project
	 * ({@code projectId}, or pages without a project when {@code onlyProject} is set
	 * and the id is null). The rule is part of the query, so the cap counts pages the
	 * caller may read rather than the first thousand of everybody's.
	 */
	public List<Article> list(User user, boolean onlyProject, String projectId) {
		return list(user, onlyProject, projectId, null, LIST_CAP);
	}

	/**
	 * As {@link #list(User, boolean, String)}, narrowed to one space when given
	 * (case-insensitive, inside the query so a cap never hides a space), reading at
	 * most {@code limit} pages.
	 */
	public List<Article> list(User user, boolean onlyProject, String projectId, String space, int limit) {
		List<Criteria> parts = new ArrayList<>(List.of(access.sightOf(user).criteria()));
		if (onlyProject) parts.add(Criteria.where("projectId").is(projectId));
		if (space != null && !space.isBlank()) {
			parts.add(Criteria.where("space").regex("^" + java.util.regex.Pattern.quote(space.trim()) + "$", "i"));
		}
		Query query = Query.query(new Criteria().andOperator(parts.toArray(Criteria[]::new)))
				.with(Sort.by("sortOrder", "_id")).limit(limit);
		return mongo.find(query, Article.class);
	}

	/** Most pages one outline carries; far more than a team keeps, and still a small answer. */
	public static final int OUTLINE_CAP = 2000;

	/** A page as a picker shows it: where it hangs and what it is called, no body. */
	public record PageRef(String id, String title, String icon, String parentId, int sortOrder) {
	}

	/**
	 * The pages of one team that {@code user} reads, as an outline for the picker a
	 * Team-Admin opens pages with; optionally only titles containing {@code q}. One
	 * more than the cap is read, so the caller can say the list was cut.
	 */
	public List<PageRef> outlineOfTeam(User user, com.ahmadre.hinata.team.Team team, String q) {
		// One team's pages: what this member reads of that team decides, not their whole reach.
		com.ahmadre.hinata.team.TeamMembership membership = team.membership(user.getId());
		if (membership == null) return List.of();
		com.ahmadre.hinata.team.KnowledgeAccess pages = membership.knowledgeOrNone();
		List<Criteria> parts = new ArrayList<>(List.of(Criteria.where("teamId").is(team.getId()),
				Criteria.where("projectId").is(null)));
		if (!membership.isAdmin() && pages.getScope() != com.ahmadre.hinata.team.ProjectAccess.Scope.ALL) {
			java.util.Set<String> granted = pages.getScope() == com.ahmadre.hinata.team.ProjectAccess.Scope.SOME
					? access.grantedInTeam(team.getId(), pages.getArticleIds()) : java.util.Set.of();
			if (granted.isEmpty()) return List.of();
			parts.add(Criteria.where("_id").in(granted));
		}
		if (q != null && !q.isBlank()) {
			parts.add(Criteria.where("title").regex(java.util.regex.Pattern.quote(q.trim()), "i"));
		}
		Query query = Query.query(new Criteria().andOperator(parts.toArray(Criteria[]::new)))
				.with(Sort.by("sortOrder", "_id")).limit(OUTLINE_CAP + 1);
		query.fields().include("_id").include("title").include("icon").include("parentId").include("sortOrder");
		return mongo.find(query, org.bson.Document.class, "articles").stream()
				.map(row -> new PageRef(com.ahmadre.hinata.common.MongoIds.of(row), row.getString("title"), row.getString("icon"),
						row.getString("parentId"), row.getInteger("sortOrder", 0)))
				.toList();
	}

	/** The fields a new page is made of. */
	public record Draft(String title, RichText body, String projectId, String teamId, boolean organization,
			String parentId, String space, String icon, List<String> tags, Integer sortOrder) {
	}

	public Article create(User author, Draft draft) {
		String projectId = draft.projectId();
		String teamId = draft.teamId();
		boolean organization = draft.organization();
		if (draft.parentId() != null) {
			Article parent = readable(draft.parentId(), author);
			projectId = parent.getProjectId();
			teamId = parent.getTeamId();
			organization = parent.isOrganizationWide();
		} else {
			// The caller must reach the target project/team, otherwise they could
			// plant pages into a place they can't read.
			access.assertCanTarget(projectId, teamId, organization, author);
		}
		RichText body = draft.body() != null ? draft.body() : RichText.EMPTY;
		Article created = articles.save(Article.builder()
				.title(draft.title())
				.content(body.text())
				.contentDoc(body.doc())
				.referencedIssueKeys(new ArrayList<>(body.issueKeys()))
				.projectId(projectId)
				.teamId(teamId)
				.organization(organization ? Boolean.TRUE : null)
				.parentId(draft.parentId())
				.space(draft.space())
				.icon(draft.icon())
				.tags(labels(draft.tags()))
				.sortOrder(draft.sortOrder() != null ? draft.sortOrder() : 0)
				.authorId(author.getId())
				.lastEditorId(author.getId())
				.build());
		history.created(created, author.getId());
		return created;
	}

	/**
	 * Saves an edited page. When {@code parentId} differs from the stored one the
	 * page moves: under a parent it takes the parent's place, subtree included; to
	 * the top it keeps its place. A move that changes the place needs the same
	 * authority as {@link #place}.
	 */
	public Article save(Article article, String parentId, User user) {
		boolean reparented = !Objects.equals(article.getParentId(), parentId);
		Place from = Place.of(article);
		Place to = from;
		if (reparented && parentId != null) {
			Article parent = readable(parentId, user);
			assertNotBelow(parent, article.getId());
			to = Place.of(parent);
			if (!to.equals(from)) {
				assertMayMove(article, to, user);
			}
			to.applyTo(article);
		}
		article.setParentId(parentId);
		article.setTags(labels(article.getTags()));
		Article before = storedForHistory(article.getId());
		boolean edited = before != null && ArticleHistory.changed(before, article);
		if (edited) article.setLastEditorId(user.getId());
		// A removed title or text written anew shows the new words again (HIN-169).
		if (edited) ArticleContentSurface.markRewritten(before, article);
		Article saved = articles.save(article);
		if (edited) history.saved(before, saved, user.getId());
		if (!to.equals(from)) moved(saved, from, user);
		return saved;
	}

	/**
	 * The stored page as far as its history needs it: what a version records and
	 * whom and when a first version falls back to. The plain-text copy, the labels
	 * and the issue links stay in the database.
	 */
	private Article storedForHistory(String id) {
		Query query = Query.query(Criteria.where("_id").is(id));
		// sortOrder rides along because the entity's constructor cannot take the primitive as null.
		query.fields().include("title").include("contentDoc").include("lastEditorId").include("authorId")
				.include("updatedAt").include("sortOrder");
		return mongo.findOne(query, Article.class);
	}

	/**
	 * Brings back the title and text of an earlier version. Restoring is an edit
	 * like any other: it becomes the newest version, and nothing in between is lost.
	 */
	public Article restore(Article article, int number, User user) {
		ArticleVersion version = history.get(article.getId(), number);
		RichText body = richText.fromLexical(version.getContentDoc());
		article.setTitle(version.getTitle());
		article.setContent(body.text());
		article.setContentDoc(body.doc());
		article.setReferencedIssueKeys(new ArrayList<>(body.issueKeys()));
		return save(article, article.getParentId(), user);
	}

	/** Labels one page carries at most, and the longest one. */
	static final int MAX_LABELS = 30;
	static final int MAX_LABEL_LENGTH = 40;

	/**
	 * Labels as stored: trimmed, inner whitespace collapsed, blanks and repeats
	 * dropped, bounded in number and length. The REST request is validated
	 * already; the MCP tools are not, and both end here.
	 */
	static List<String> labels(List<String> raw) {
		if (raw == null) return new ArrayList<>();
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (String label : raw) {
			if (label == null) continue;
			String clean = label.strip().replaceAll("\\s+", " ");
			if (clean.isEmpty()) continue;
			out.add(clean.length() > MAX_LABEL_LENGTH ? clean.substring(0, MAX_LABEL_LENGTH) : clean);
			if (out.size() == MAX_LABELS) break;
		}
		return new ArrayList<>(out);
	}

	/** Most pages one reorder touches; a level of a tree, not a whole space. */
	public static final int MAX_ORDERED = 500;

	/**
	 * Puts sibling pages in the given order. Every page must be readable and share
	 * one parent, and pages at the top must share one space: an order only means
	 * something among the pages shown side by side.
	 */
	public void order(List<String> ids, User user) {
		if (ids == null || ids.isEmpty()) return;
		if (ids.size() > MAX_ORDERED || new java.util.HashSet<>(ids).size() != ids.size()) {
			throw ApiException.badRequest("error.article.orderInvalid");
		}
		// One read for the whole level, through the same rule a listing applies, and
		// only the fields the check needs. A page the caller does not read is simply
		// not found, which is the same 404 as a page that does not exist.
		Query level = Query.query(new Criteria().andOperator(Criteria.where("_id").in(ids),
				access.sightOf(user).criteria()));
		level.fields().include("_id").include("parentId").include("space");
		List<org.bson.Document> pages = mongo.find(level, org.bson.Document.class, "articles");
		if (pages.size() != ids.size()) throw ApiException.notFound("article");
		org.bson.Document first = pages.getFirst();
		for (org.bson.Document page : pages) {
			boolean sameLevel = Objects.equals(page.getString("parentId"), first.getString("parentId"))
					&& (first.getString("parentId") != null
							|| sameSpace(page.getString("space"), first.getString("space")));
			if (!sameLevel) throw ApiException.badRequest("error.article.orderNotSiblings");
		}
		org.springframework.data.mongodb.core.BulkOperations bulk = mongo.bulkOps(
				org.springframework.data.mongodb.core.BulkOperations.BulkMode.UNORDERED, Article.class);
		for (int i = 0; i < ids.size(); i++) {
			bulk.updateOne(Query.query(Criteria.where("_id").is(ids.get(i))), new Update().set("sortOrder", i));
		}
		bulk.execute();
	}

	private static boolean sameSpace(String a, String b) {
		return a == null ? b == null : b != null && a.trim().equalsIgnoreCase(b.trim());
	}

	/**
	 * Moves a top-level page, with everything filed under it, into a project, a
	 * team, the whole organisation or its author's private pages. A page further
	 * down lives where its parent lives and moves only with it.
	 */
	public Article place(String id, String projectId, String teamId, boolean organization, User user) {
		Article article = readable(id, user);
		if (article.getParentId() != null) {
			throw ApiException.badRequest("error.article.placeFollowsParent");
		}
		Place from = Place.of(article);
		Place to = Place.of(projectId, teamId, organization, article.getAuthorId());
		if (to.equals(from)) return article;
		assertMayMove(article, to, user);
		access.assertCanTarget(projectId, teamId, organization, user);
		to.applyTo(article);
		Article saved = articles.save(article);
		moved(saved, from, user);
		return saved;
	}

	/**
	 * Where a page lives: a project, a team, the whole organisation, or somebody's
	 * private pages. A private place belongs to one author: two private pages of two
	 * people are in two places, so nothing that moves one person's page ever carries
	 * another person's along.
	 */
	record Place(String projectId, String teamId, boolean organization, String ownerId) {

		static Place of(Article article) {
			return of(article.getProjectId(), article.getTeamId(), article.isOrganizationWide(),
					article.getAuthorId());
		}

		static Place of(String projectId, String teamId, boolean organization, String authorId) {
			boolean shared = projectId != null || teamId != null || organization;
			return new Place(projectId, teamId, organization, shared ? null : authorId);
		}

		boolean isPrivate() {
			return projectId == null && teamId == null && !organization;
		}

		void applyTo(Article article) {
			article.setProjectId(projectId);
			article.setTeamId(teamId);
			article.setOrganization(organization ? Boolean.TRUE : null);
		}
	}

	/**
	 * Taking a page out of its place changes who reads it, for everybody there, so
	 * reading it is not enough. Out of a project: its leads and the Team-Admins of a
	 * team owning it. Out of a team: that team's admins. Out of the organisation: the
	 * author and the organisation's admins. Out of private pages: the author. And a
	 * page only becomes private as its own author's.
	 */
	private void assertMayMove(Article article, Place to, User user) {
		boolean author = user.getId().equals(article.getAuthorId());
		if (to.isPrivate() && !author) {
			throw ApiException.forbidden("error.article.privateIsAuthorOnly");
		}
		boolean mayTakeAway;
		if (article.getProjectId() != null) {
			mayTakeAway = projects.findOptional(article.getProjectId())
					.map(project -> projects.canManage(project, user)).orElse(false);
		} else if (article.getTeamId() != null) {
			mayTakeAway = teams.findById(article.getTeamId())
					.map(team -> team.isAdmin(user.getId())).orElse(false);
		} else if (article.isOrganizationWide()) {
			mayTakeAway = author || user.isAdmin() || user.isOrgAdmin();
		} else {
			mayTakeAway = author;
		}
		if (!mayTakeAway) {
			throw ApiException.forbidden("error.article.moveNotAllowed");
		}
	}

	/** After a move: the subtree follows, and the move is on record. */
	private void moved(Article root, Place from, User user) {
		moveSubtree(root, from);
		audit.event(AuditAction.ARTICLE_MOVED).actor(user)
				.meta("article", root.getId())
				.meta("from", describe(from)).meta("to", describe(Place.of(root))).log();
	}

	private static String describe(Place place) {
		if (place.projectId() != null) return "project:" + place.projectId();
		if (place.teamId() != null) return "team:" + place.teamId();
		if (place.organization()) return "organization";
		return "private";
	}

	public void delete(Article article) {
		if (!articles.findByParentId(article.getId()).isEmpty()) {
			throw ApiException.conflict("error.article.hasChildren");
		}
		articles.deleteById(article.getId());
		history.deleteOf(List.of(article.getId()));
		reading.deleteOf(List.of(article.getId()));
	}

	/**
	 * Removes every page of a deleted project, with their saved versions and
	 * everybody's favourites and recently opened marks of them: those rows name
	 * the pages and would otherwise outlive them for good. Returns how many pages
	 * went.
	 */
	public long deleteAllOfProject(String projectId) {
		Query ofProject = Query.query(Criteria.where("projectId").is(projectId));
		ofProject.fields().include("_id");
		List<String> ids = mongo.find(ofProject, org.bson.Document.class, "articles").stream()
				.map(com.ahmadre.hinata.common.MongoIds::of).toList();
		if (ids.isEmpty()) return 0;
		mongo.remove(Query.query(Criteria.where("_id").in(ids)), Article.class);
		history.deleteOf(ids);
		reading.deleteOf(ids);
		return ids.size();
	}

	/** Refuses to file a page under itself or under one of its own descendants. */
	private void assertNotBelow(Article parent, String articleId) {
		Article cursor = parent;
		for (int depth = 0; cursor != null && depth < MAX_DEPTH; depth++) {
			if (articleId.equals(cursor.getId())) {
				throw ApiException.badRequest("error.article.cycle");
			}
			cursor = cursor.getParentId() == null ? null : articles.findById(cursor.getParentId()).orElse(null);
		}
	}

	/**
	 * Gives the pages below {@code root} the root's new place, level by level — but
	 * only those that lived where the root lived before. Pages filed before places
	 * were kept together can sit in another project, team or person's private pages
	 * than their parent;
	 * carrying them along would hand them to people who could not read them, so
	 * they stay where they are and become top-level pages there instead.
	 */
	private void moveSubtree(Article root, Place from) {
		List<String> level = List.of(root.getId());
		for (int depth = 0; !level.isEmpty() && depth < MAX_DEPTH; depth++) {
			Query below = Query.query(Criteria.where("parentId").in(level));
			below.fields().include("_id").include("projectId").include("teamId").include("organization")
					.include("authorId");
			List<String> follow = new ArrayList<>();
			List<String> stay = new ArrayList<>();
			for (org.bson.Document row : mongo.find(below, org.bson.Document.class, "articles")) {
				Place place = Place.of(row.getString("projectId"), row.getString("teamId"),
						Boolean.TRUE.equals(row.getBoolean("organization")), row.getString("authorId"));
				(place.equals(from) ? follow : stay).add(com.ahmadre.hinata.common.MongoIds.of(row));
			}
			if (!stay.isEmpty()) {
				mongo.updateMulti(Query.query(Criteria.where("_id").in(stay)), new Update().unset("parentId"),
						Article.class);
			}
			if (follow.isEmpty()) return;
			mongo.updateMulti(Query.query(Criteria.where("_id").in(follow)),
					new Update().set("projectId", root.getProjectId()).set("teamId", root.getTeamId())
							.set("organization", root.isOrganizationWide() ? Boolean.TRUE : null),
					Article.class);
			level = follow;
		}
	}
}
