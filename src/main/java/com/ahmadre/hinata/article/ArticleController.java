package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

	private final ArticleRepository articles;
	private final CurrentUser currentUser;
	private final ProjectService projectService;
	private final TeamService teamService;

	public record ArticleRequest(
			@NotBlank @Size(max = 300) String title,
			@Size(max = 100000) String content,
			String projectId,
			String teamId,
			String parentId,
			@Size(max = 60) String space,
			@Size(max = 60) String icon,
			List<String> tags,
			Integer sortOrder) {
	}

	@GetMapping
	public List<Article> list(@RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "false") boolean all) {
		User user = currentUser.require();
		final List<Article> base;
		if (all) {
			base = articles.findAllByOrderBySortOrderAsc();
		} else if (projectId != null) {
			base = articles.findByProjectIdOrderBySortOrderAsc(projectId);
		} else {
			base = articles.findByProjectIdIsNullOrderBySortOrderAsc();
		}
		return filterVisible(base, user);
	}

	@GetMapping("/{id}")
	public Article get(@PathVariable String id) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			// Don't leak existence of articles the user has no access to.
			throw ApiException.notFound("article");
		}
		return article;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Article create(@RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		// Write-side ACL: the caller must be able to see the target project/team,
		// otherwise they could plant KB content into a space they can't access.
		assertCanTarget(request.projectId(), request.teamId(), user);
		return articles.save(Article.builder()
				.title(request.title())
				.content(request.content())
				.projectId(request.projectId())
				.teamId(request.teamId())
				.parentId(request.parentId())
				.space(request.space())
				.icon(request.icon())
				.tags(request.tags() != null ? request.tags() : List.of())
				.sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
				.authorId(userId)
				.build());
	}

	@PatchMapping("/{id}")
	public Article update(@PathVariable String id, @RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			throw ApiException.notFound("article");
		}
		// The caller must also be able to see the TARGET project/team — otherwise
		// an article could be relocated into a space the caller can't access.
		assertCanTarget(request.projectId(), request.teamId(), user);
		article.setTitle(request.title());
		if (request.content() != null) article.setContent(request.content());
		article.setProjectId(request.projectId());
		article.setTeamId(request.teamId());
		article.setParentId(request.parentId());
		if (request.space() != null) article.setSpace(request.space());
		if (request.icon() != null) article.setIcon(request.icon());
		if (request.tags() != null) article.setTags(request.tags());
		if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());
		return articles.save(article);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			throw ApiException.notFound("article");
		}
		if (!articles.findByParentId(id).isEmpty()) {
			throw ApiException.conflict("error.article.hasChildren");
		}
		articles.deleteById(id);
	}

	// ── visibility ──────────────────────────────────────────────────────────

	/**
	 * Articles (and therefore their spaces) are visible when:
	 * <ul>
	 *   <li>project-scoped → the user has access to that project (direct member
	 *       or via a team grant), or</li>
	 *   <li>team-scoped → the user belongs to that team, or</li>
	 *   <li>global (no project, no team) → visible to every authenticated user.</li>
	 * </ul>
	 * Platform admins see everything.
	 */
	private List<Article> filterVisible(List<Article> base, User user) {
		if (user.isAdmin()) {
			return base;
		}
		Set<String> projectIds = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> teamIds = teamService.visibleTo(user).stream()
				.map(Team::getId).collect(Collectors.toSet());
		return base.stream()
				.filter(a -> canSee(a, projectIds, teamIds))
				.toList();
	}

	/**
	 * Guards the write-side target scope: a non-admin caller may only create/move
	 * an article into a project or team they can actually see. Global articles
	 * (null project + null team) stay creatable by any authenticated user.
	 */
	private void assertCanTarget(String projectId, String teamId, User user) {
		if (user.isAdmin()) {
			return;
		}
		if (projectId != null && projectService.visibleTo(user).stream()
				.noneMatch(p -> p.getId().equals(projectId))) {
			throw ApiException.forbidden("error.accessDenied");
		}
		if (teamId != null && teamService.visibleTo(user).stream()
				.noneMatch(t -> t.getId().equals(teamId))) {
			throw ApiException.forbidden("error.accessDenied");
		}
	}

	private boolean canSee(Article article, User user) {
		if (user.isAdmin()) {
			return true;
		}
		Set<String> projectIds = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> teamIds = teamService.visibleTo(user).stream()
				.map(Team::getId).collect(Collectors.toSet());
		return canSee(article, projectIds, teamIds);
	}

	private boolean canSee(Article article, Set<String> projectIds, Set<String> teamIds) {
		if (article.getProjectId() != null) {
			return projectIds.contains(article.getProjectId());
		}
		if (article.getTeamId() != null) {
			return teamIds.contains(article.getTeamId());
		}
		return true; // global / organisation-wide
	}
}
