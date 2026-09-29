package com.ahmadre.hinata.article;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.KnowledgeAccess;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamKnowledge;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one answer to "may this person read that knowledge-base page". The REST
 * routes, the MCP tools and the global search all ask here, so the three can never
 * disagree about what somebody sees.
 *
 * <p>A page belongs to exactly one place, and that place decides:
 * <ul>
 *   <li><b>a project</b> — everybody who sees the project reads it;</li>
 *   <li><b>a team</b> — the team's admins read all of it; a member reads what their
 *       {@link KnowledgeAccess} opens (all pages, some pages with everything filed
 *       under them, or none — the default);</li>
 *   <li><b>nobody else</b> (no project, no team) — a private page: only its author.</li>
 * </ul>
 * Platform and organisation admins get nothing extra. A page used to be readable by
 * every account when it had no project or team; that is exactly the default this
 * rule closes.
 *
 * <p>Writing follows reading: whoever reads a page may edit it, as in any wiki.
 * Moving a page into another place needs {@link #assertCanTarget}.
 */
@Component
@RequiredArgsConstructor
public class ArticleAccess implements TeamKnowledge {

	private static final String ARTICLES = "articles";

	private final ProjectService projects;
	private final TeamRepository teams;
	private final MongoTemplate mongo;

	/** The id of a raw article row, whichever way the driver hands it over. */
	static String id(Document row) {
		Object id = row.get("_id");
		return id instanceof org.bson.types.ObjectId oid ? oid.toHexString() : String.valueOf(id);
	}

	/** What one person reads, worked out once per request. */
	public record Sight(String userId, Set<String> projectIds, Set<String> wholeTeams,
			Set<String> grantedPages) {

		public boolean canSee(Article article) {
			if (article == null) return false;
			if (article.getProjectId() != null) {
				return projectIds.contains(article.getProjectId());
			}
			if (article.getTeamId() != null) {
				return wholeTeams.contains(article.getTeamId()) || grantedPages.contains(article.getId());
			}
			return userId.equals(article.getAuthorId());
		}

		/** The same rule as a query, for listings and search. */
		public Criteria criteria() {
			List<Criteria> any = new ArrayList<>();
			any.add(Criteria.where("projectId").is(null).and("teamId").is(null).and("authorId").is(userId));
			if (!projectIds.isEmpty()) any.add(Criteria.where("projectId").in(projectIds));
			if (!wholeTeams.isEmpty()) {
				any.add(Criteria.where("projectId").is(null).and("teamId").in(wholeTeams));
			}
			if (!grantedPages.isEmpty()) {
				any.add(Criteria.where("projectId").is(null).and("_id").in(grantedPages));
			}
			return new Criteria().orOperator(any.toArray(Criteria[]::new));
		}

		public List<Article> filter(Collection<Article> articles) {
			return articles.stream().filter(this::canSee).toList();
		}
	}

	public Sight sightOf(User user) {
		Set<String> projectIds = projects.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> wholeTeams = new HashSet<>();
		Map<String, List<String>> someByTeam = new HashMap<>();
		for (Team team : teams.findByMembersUserId(user.getId())) {
			TeamMembership membership = team.membership(user.getId());
			if (membership == null) continue;
			KnowledgeAccess pages = membership.knowledgeOrNone();
			if (membership.isAdmin() || pages.getScope() == ProjectAccess.Scope.ALL) {
				wholeTeams.add(team.getId());
			} else if (pages.getScope() == ProjectAccess.Scope.SOME && pages.getArticleIds() != null
					&& !pages.getArticleIds().isEmpty()) {
				someByTeam.put(team.getId(), pages.getArticleIds());
			}
		}
		return new Sight(user.getId(), projectIds, wholeTeams, grantedWithDescendants(someByTeam));
	}

	public boolean canSee(Article article, User user) {
		return sightOf(user).canSee(article);
	}

	/**
	 * A page may only be filed into a place the author can reach: a project they
	 * see, or a team they belong to. No place at all means a private page, which
	 * anybody may keep.
	 */
	public void assertCanTarget(String projectId, String teamId, User user) {
		if (projectId != null && teamId != null) {
			throw ApiException.badRequest("error.article.oneScope");
		}
		if (projectId != null && !projects.visibleTo(user).stream().map(Project::getId)
				.collect(Collectors.toSet()).contains(projectId)) {
			throw ApiException.forbidden("error.accessDenied");
		}
		if (teamId != null && teams.findById(teamId).map(team -> !team.isMember(user.getId())).orElse(true)) {
			throw ApiException.forbidden("error.accessDenied");
		}
	}

	@Override
	public Set<String> pagesOf(String teamId, Collection<String> articleIds) {
		Query query = Query.query(Criteria.where("_id").in(articleIds).and("teamId").is(teamId)
				.and("projectId").is(null));
		query.fields().include("_id");
		// Raw documents: a projection leaves out the primitive sortOrder, which the
		// entity's constructor cannot take as null.
		return mongo.find(query, Document.class, ARTICLES).stream().map(ArticleAccess::id)
				.collect(Collectors.toSet());
	}

	/**
	 * A SOME grant opens a page and everything filed under it. Pages of one team
	 * form their own trees (a page always takes its parent's place), so the walk
	 * reads each granting team's pages once, ids and parents only.
	 */
	private Set<String> grantedWithDescendants(Map<String, List<String>> someByTeam) {
		if (someByTeam.isEmpty()) return Set.of();
		Query query = Query.query(Criteria.where("teamId").in(someByTeam.keySet()).and("projectId").is(null));
		query.fields().include("_id").include("parentId").include("teamId");
		Map<String, List<String>> children = new HashMap<>();
		Map<String, String> teamOf = new HashMap<>();
		for (Document page : mongo.find(query, Document.class, ARTICLES)) {
			String id = id(page);
			teamOf.put(id, page.getString("teamId"));
			String parentId = page.getString("parentId");
			if (parentId != null) {
				children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
			}
		}
		Set<String> granted = new HashSet<>();
		someByTeam.forEach((teamId, roots) -> {
			Deque<String> open = new ArrayDeque<>();
			for (String root : roots) {
				if (teamId.equals(teamOf.get(root))) open.add(root);
			}
			while (!open.isEmpty()) {
				String id = open.pop();
				if (!granted.add(id)) continue;
				for (String child : children.getOrDefault(id, List.of())) {
					if (teamId.equals(teamOf.get(child))) open.add(child);
				}
			}
		});
		return granted;
	}
}
