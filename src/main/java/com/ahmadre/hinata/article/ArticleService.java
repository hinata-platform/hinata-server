package com.ahmadre.hinata.article;

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
		List<Criteria> parts = new ArrayList<>(List.of(access.sightOf(user).criteria()));
		if (onlyProject) parts.add(Criteria.where("projectId").is(projectId));
		Query query = Query.query(new Criteria().andOperator(parts.toArray(Criteria[]::new)))
				.with(Sort.by("sortOrder", "_id")).limit(LIST_CAP);
		return mongo.find(query, Article.class);
	}

	/** The pages of one team that {@code user} reads — all of them for its admins. */
	public List<Article> listOfTeam(User user, String teamId) {
		Query query = Query.query(new Criteria().andOperator(access.sightOf(user).criteria(),
				Criteria.where("teamId").is(teamId), Criteria.where("projectId").is(null)))
				.with(Sort.by("sortOrder", "_id")).limit(LIST_CAP);
		return mongo.find(query, Article.class);
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
	 * the top it keeps its place.
	 */
	public Article save(Article article, String parentId, User user) {
		boolean reparented = !Objects.equals(article.getParentId(), parentId);
		if (reparented && parentId != null) {
			Article parent = readable(parentId, user);
			assertNotBelow(parent, article.getId());
			article.setProjectId(parent.getProjectId());
			article.setTeamId(parent.getTeamId());
		}
		article.setParentId(parentId);
		Article saved = articles.save(article);
		if (reparented && parentId != null) moveSubtree(saved);
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
		// A private page is somebody's own: nobody files another person's page away.
		if (projectId == null && teamId == null && !user.getId().equals(article.getAuthorId())) {
			throw ApiException.forbidden("error.article.privateIsAuthorOnly");
		}
		access.assertCanTarget(projectId, teamId, user);
		article.setProjectId(projectId);
		article.setTeamId(teamId);
		Article saved = articles.save(article);
		moveSubtree(saved);
		return saved;
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

	/** Gives every page below {@code root} the root's place, level by level. */
	private void moveSubtree(Article root) {
		List<String> level = List.of(root.getId());
		for (int depth = 0; !level.isEmpty() && depth < MAX_DEPTH; depth++) {
			Query below = Query.query(Criteria.where("parentId").in(level));
			below.fields().include("_id");
			List<String> ids = mongo.find(below, org.bson.Document.class, "articles").stream()
					.map(ArticleAccess::id).toList();
			if (ids.isEmpty()) return;
			mongo.updateMulti(Query.query(Criteria.where("_id").in(ids)),
					new Update().set("projectId", root.getProjectId()).set("teamId", root.getTeamId()),
					Article.class);
			level = ids;
		}
	}
}
