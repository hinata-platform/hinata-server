package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Read-only MCP tools over projects. Gates on {@code projects:read}, resolves
 * the caller via {@link CurrentUser} and delegates to {@link ProjectService} so
 * project visibility / membership ACLs apply. Returns a lean {@link ProjectView}
 * that deliberately excludes the Git connection block (repository tokens and
 * webhook secrets) — those must never reach a client.
 */
@Service
@RequiredArgsConstructor
public class ProjectTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final ProjectService projectService;
	// Read-only lookup by key; the ACL is still enforced via ProjectService.assertMember.
	private final ProjectRepository projects;

	@McpTool(name = "list_projects", title = "List projects",
			description = "List the projects the caller can access (direct member or via a "
					+ "team grant), excluding archived projects.")
	public List<ProjectView> listProjects() {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		return projectService.visibleTo(user).stream().map(ProjectView::of).toList();
	}

	@McpTool(name = "get_project", title = "Get a project",
			description = "Fetch a single project by its id or its key (e.g. ASTA). Fails if "
					+ "the caller is not a member of the project.")
	public ProjectView getProject(
			@McpToolParam(description = "Project id or key (e.g. ASTA)") String idOrKey) {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		Project project = projects.findById(idOrKey)
				.or(() -> projects.findByKeyIgnoreCase(idOrKey))
				.orElseThrow(() -> ApiException.notFound("project"));
		projectService.assertMember(project, user);
		return ProjectView.of(project);
	}

	/**
	 * A lean project projection. Excludes the Git connection block (encrypted
	 * tokens / webhook secrets) and other internals; carries only names for
	 * workflow states and labels, and a member count rather than raw ids.
	 */
	private record ProjectView(
			String id, String key, String name, String description,
			String leadId, List<String> leadIds, int memberCount,
			List<String> workflowStates, List<String> resolvedStates, List<String> labels,
			String color, boolean archived, Instant createdAt, Instant updatedAt) {

		static ProjectView of(Project p) {
			return new ProjectView(
					p.getId(), p.getKey(), p.getName(), p.getDescription(),
					p.getLeadId(), p.getLeadIds(),
					p.getMemberIds() == null ? 0 : p.getMemberIds().size(),
					p.workflowStateNames(), p.getResolvedStates(), p.labelNames(),
					p.getColor(), p.isArchived(), p.getCreatedAt(), p.getUpdatedAt());
		}
	}
}
