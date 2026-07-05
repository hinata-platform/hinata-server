package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only MCP tool over the knowledge base. Gates on {@code kb:read} and
 * applies the exact same visibility rule the {@code ArticleController} enforces:
 * a project-scoped article is visible only to members of that project, a
 * team-scoped article only to that team's members, a global article to every
 * authenticated user; admins see all. Access denial is reported as "not found"
 * so the tool never leaks the existence of an article the caller cannot see.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeReadTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final ArticleRepository articles;
	private final ProjectService projectService;
	private final TeamService teamService;

	@McpTool(name = "read_kb_article", title = "Read a knowledge base article",
			description = "Fetch a knowledge base article's markdown content by id. Fails with "
					+ "not-found if the caller has no access to the article's project or team.")
	public ArticleView readKbArticle(
			@McpToolParam(description = "Article id") String id) {
		scopeGuard.require(Scopes.KB_READ);
		User user = currentUser.require();
		return ArticleView.of(requireVisible(id, user));
	}

	/**
	 * Loads an article and enforces the caller's visibility, mirroring the
	 * knowledge-base controller. Shared with {@link HinataResources}. Throws
	 * 404 both when the article is missing and when it is hidden from the caller.
	 */
	Article requireVisible(String id, User user) {
		Article article = articles.findById(id)
				.orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			throw ApiException.notFound("article");
		}
		return article;
	}

	private boolean canSee(Article article, User user) {
		if (user.isAdmin()) {
			return true;
		}
		if (article.getProjectId() != null) {
			Set<String> projectIds = projectService.visibleTo(user).stream()
					.map(Project::getId).collect(Collectors.toSet());
			return projectIds.contains(article.getProjectId());
		}
		if (article.getTeamId() != null) {
			Set<String> teamIds = teamService.visibleTo(user).stream()
					.map(Team::getId).collect(Collectors.toSet());
			return teamIds.contains(article.getTeamId());
		}
		return true; // global / organisation-wide
	}

	/** Lean article projection — the markdown content plus placement metadata. */
	public record ArticleView(
			String id, String title, String content, String space, String icon,
			String projectId, String teamId, String parentId, List<String> tags,
			String authorId, Instant createdAt, Instant updatedAt) {

		static ArticleView of(Article a) {
			return new ArticleView(
					a.getId(), a.getTitle(), a.getContent(), a.getSpace(), a.getIcon(),
					a.getProjectId(), a.getTeamId(), a.getParentId(), a.getTags(),
					a.getAuthorId(), a.getCreatedAt(), a.getUpdatedAt());
		}
	}
}
