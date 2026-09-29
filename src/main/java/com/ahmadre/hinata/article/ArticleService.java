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
	public record Draft(String title, RichText body, String projectId, String teamId, String parentId,
			String space, String icon, List<String> tags, Integer sortOrder) {
	}

	public Article create(User author, Draft draft) {
		String projectId = draft.projectId();
		String teamId = draft.teamId();
		if (draft.parentId() != null) {
			Article parent = readable(draft.parentId(), author);
			projectId = parent.getProjectId();
			teamId = parent.getTeamId();
		} else {
			// The caller must reach the target project/team, otherwise they could
			// plant pages into a place they can't read.
			access.assertCanTarget(projectId, teamId, author);
		}
		RichText body = draft.body() != null ? draft.body() : RichText.EMPTY;
		return articles.save(Article.builder()
				.title(draft.title())
				.content(body.text())
				.contentDoc(body.doc())
				.referencedIssueKeys(new ArrayList<>(body.issueKeys()))
				.projectId(projectId)
				.teamId(teamId)
				.parentId(draft.parentId())
				.space(draft.space())
				.icon(draft.icon())
				.tags(draft.tags() != null ? draft.tags() : List.of())
				.sortOrder(draft.sortOrder() != null ? draft.sortOrder() : 0)
				.authorId(author.getId())
				.build());
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
			article.setProjectId(to.projectId());
			article.setTeamId(to.teamId());
		}
		article.setParentId(parentId);
		Article saved = articles.save(article);
		if (!to.equals(from)) moved(saved, from, user);
		return saved;
	}

	/**
	 * Moves a top-level page, with everything filed under it, into a project, a
	 * team or its author's private pages. A page further down lives where its
	 * parent lives and moves only with it.
	 */
	public Article place(String id, String projectId, String teamId, User user) {
		Article article = readable(id, user);
		if (article.getParentId() != null) {
			throw ApiException.badRequest("error.article.placeFollowsParent");
		}
		Place from = Place.of(article);
		Place to = Place.of(projectId, teamId, article.getAuthorId());
		if (to.equals(from)) return article;
		assertMayMove(article, to, user);
		access.assertCanTarget(projectId, teamId, user);
		article.setProjectId(projectId);
		article.setTeamId(teamId);
		Article saved = articles.save(article);
		moved(saved, from, user);
		return saved;
	}

	/**
	 * Where a page lives: a project, a team, or somebody's private pages. A private
	 * place belongs to one author: two private pages of two people are in two places,
	 * so nothing that moves one person's page ever carries another person's along.
	 */
	record Place(String projectId, String teamId, String ownerId) {

		static Place of(Article article) {
			return of(article.getProjectId(), article.getTeamId(), article.getAuthorId());
		}

		static Place of(String projectId, String teamId, String authorId) {
			return new Place(projectId, teamId, projectId == null && teamId == null ? authorId : null);
		}

		boolean isPrivate() {
			return projectId == null && teamId == null;
		}
	}

	/**
	 * Taking a page out of its place changes who reads it, for everybody there, so
	 * reading it is not enough. Out of a project: its leads and the Team-Admins of a
	 * team owning it. Out of a team: that team's admins. Out of private pages: the
	 * author. And a page only becomes private as its own author's.
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
		return "private";
	}

	public void delete(Article article) {
		if (!articles.findByParentId(article.getId()).isEmpty()) {
			throw ApiException.conflict("error.article.hasChildren");
		}
		articles.deleteById(article.getId());
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
			below.fields().include("_id").include("projectId").include("teamId").include("authorId");
			List<String> follow = new ArrayList<>();
			List<String> stay = new ArrayList<>();
			for (org.bson.Document row : mongo.find(below, org.bson.Document.class, "articles")) {
				Place place = Place.of(row.getString("projectId"), row.getString("teamId"), row.getString("authorId"));
				(place.equals(from) ? follow : stay).add(com.ahmadre.hinata.common.MongoIds.of(row));
			}
			if (!stay.isEmpty()) {
				mongo.updateMulti(Query.query(Criteria.where("_id").in(stay)), new Update().unset("parentId"),
						Article.class);
			}
			if (follow.isEmpty()) return;
			mongo.updateMulti(Query.query(Criteria.where("_id").in(follow)),
					new Update().set("projectId", root.getProjectId()).set("teamId", root.getTeamId()),
					Article.class);
			level = follow;
		}
	}
}
