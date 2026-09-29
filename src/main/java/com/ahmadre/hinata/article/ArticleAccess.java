package com.ahmadre.hinata.article;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.MongoIds;
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

import java.util.ArrayList;
import java.util.Collection;
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
 * <p>The rule comes in two forms, one per question: {@link #canSee(Article, User)}
 * for one page, answered from that page's place alone, and {@link Sight} as a query
 * for listings and search. Both treat an archived project as unreadable, and both
 * open a SOME grant's pages below it; the query stops walking a grant at
 * {@value #MAX_GRANTED} pages, far beyond what one grant opens, where the per-page
 * check does not.
 *
 * <p>Writing follows reading: whoever reads a page may edit it, as in any wiki.
 * Moving a page into another place needs authority over where it is now
 * ({@code ArticleService.assertMayMove}) and a place the author can reach
 * ({@link #assertCanTarget}).
 */
@Component
@RequiredArgsConstructor
public class ArticleAccess implements TeamKnowledge {

	private static final String ARTICLES = "articles";

	/** Deeper than any tree a person builds; bounds the walks below. */
	private static final int MAX_DEPTH = 50;

	/** More pages than one grant opens in practice; keeps the id list in a query bounded. */
	private static final int MAX_GRANTED = 5_000;

	private final ProjectService projects;
	private final TeamRepository teams;
	private final MongoTemplate mongo;


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
		return sightOf(user, projects.visibleTo(user).stream().map(Project::getId).collect(Collectors.toSet()));
	}

	/** As {@link #sightOf(User)}, for a caller that already knows the projects the user sees. */
	public Sight sightOf(User user, Set<String> projectIds) {
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

	/**
	 * The rule for one page, answered by its place alone: a project page asks the
	 * project, a team page asks that one team (and, for a SOME grant, the page's own
	 * ancestors), a private page compares the author. Opening a page costs a lookup or
	 * two, not the reader's whole reach.
	 */
	public boolean canSee(Article article, User user) {
		if (article == null || user == null) return false;
		if (article.getProjectId() != null) {
			// Archived like everywhere else: a deactivated project's pages do not open by id
			// when they never show in a list.
			return projects.findOptional(article.getProjectId())
					.map(project -> !project.isArchived() && projects.canSee(project, user)).orElse(false);
		}
		if (article.getTeamId() != null) {
			TeamMembership membership = teams.findById(article.getTeamId())
					.map(team -> team.membership(user.getId())).orElse(null);
			if (membership == null) return false;
			KnowledgeAccess pages = membership.knowledgeOrNone();
			if (membership.isAdmin() || pages.getScope() == ProjectAccess.Scope.ALL) return true;
			return pages.getScope() == ProjectAccess.Scope.SOME && pages.getArticleIds() != null
					&& grantedThroughAncestor(article, article.getTeamId(), Set.copyOf(pages.getArticleIds()));
		}
		return user.getId().equals(article.getAuthorId());
	}

	/** Whether the page or one of its ancestors in the same team is among [granted]. */
	private boolean grantedThroughAncestor(Article article, String teamId, Set<String> granted) {
		String id = article.getId();
		String parentId = article.getParentId();
		for (int depth = 0; id != null && depth < MAX_DEPTH; depth++) {
			if (granted.contains(id)) return true;
			if (parentId == null) return false;
			Query query = Query.query(Criteria.where("_id").is(parentId).and("teamId").is(teamId));
			query.fields().include("_id").include("parentId");
			Document parent = mongo.findOne(query, Document.class, ARTICLES);
			if (parent == null) return false;
			id = MongoIds.of(parent);
			parentId = parent.getString("parentId");
		}
		return false;
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
		return mongo.find(query, Document.class, ARTICLES).stream().map(MongoIds::of)
				.collect(Collectors.toSet());
	}

	/**
	 * A SOME grant opens a page and everything filed under it. Walked down from the
	 * granted pages level by level (ids only, through the {@code parentId} index), so
	 * the cost follows the size of what was granted, not the size of the team's pages.
	 */
	/** The pages a SOME grant opens in one team: the granted ones and everything below them. */
	Set<String> grantedInTeam(String teamId, List<String> roots) {
		return roots == null || roots.isEmpty() ? Set.of() : grantedWithDescendants(Map.of(teamId, roots));
	}

	private Set<String> grantedWithDescendants(Map<String, List<String>> someByTeam) {
		Set<String> granted = new HashSet<>();
		someByTeam.forEach((teamId, roots) -> {
			Query rootsQuery = Query.query(Criteria.where("_id").in(roots).and("teamId").is(teamId)
					.and("projectId").is(null));
			rootsQuery.fields().include("_id");
			List<String> level = mongo.find(rootsQuery, Document.class, ARTICLES).stream()
					.map(MongoIds::of).toList();
			for (int depth = 0; !level.isEmpty() && depth < MAX_DEPTH && granted.size() < MAX_GRANTED; depth++) {
				granted.addAll(level);
				Query below = Query.query(Criteria.where("parentId").in(level).and("teamId").is(teamId)
						.and("projectId").is(null));
				below.fields().include("_id");
				level = mongo.find(below, Document.class, ARTICLES).stream().map(MongoIds::of)
						.filter(id -> !granted.contains(id)).toList();
			}
		});
		return granted;
	}
}
