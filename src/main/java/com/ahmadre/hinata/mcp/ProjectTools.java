package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private final IssueRepository issues;
	private final UserRepository users;

	@McpTool(name = "list_projects", title = "List projects",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the projects the caller can access (direct member or via a "
					+ "team grant), excluding archived projects.")
	public List<ProjectView> listProjects() {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		return projectService.visibleTo(user).stream().map(ProjectView::of).toList();
	}

	@McpTool(name = "get_project", title = "Get a project",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Fetch a single project by its id or its key (e.g. ASTA). Fails if "
					+ "the caller is not a member of the project.")
	public ProjectView getProject(
			@McpToolParam(description = "Project id or key (e.g. ASTA)") String idOrKey) {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		return ProjectView.of(accessibleProject(idOrKey, user));
	}

	@McpTool(name = "list_project_members", title = "List project members",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the members of a project (id, username, display name, title and "
					+ "whether they are a project lead) — useful for resolving assignee user ids. "
					+ "Fails if the caller is not a member of the project.")
	public List<MemberView> listProjectMembers(
			@McpToolParam(description = "Project id or key (e.g. ASTA)") String idOrKey) {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		Project project = accessibleProject(idOrKey, user);
		Set<String> leadIds = new LinkedHashSet<>();
		if (project.getLeadId() != null) leadIds.add(project.getLeadId());
		if (project.getLeadIds() != null) leadIds.addAll(project.getLeadIds());
		Set<String> memberIds = new LinkedHashSet<>(leadIds);
		if (project.getMemberIds() != null) memberIds.addAll(project.getMemberIds());
		List<MemberView> members = new ArrayList<>();
		users.findAllById(memberIds).forEach(member -> members.add(new MemberView(
				member.getId(), member.getUsername(), member.getDisplayName(),
				member.getTitle(), leadIds.contains(member.getId()))));
		return members;
	}

	/** A project member as shown in the app's people pickers, plus the lead flag. */
	public record MemberView(String id, String username, String displayName, String title,
			boolean lead) {
	}

	@McpTool(name = "get_project_metrics", title = "Get project metrics",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Issue statistics for a project: total issues, resolved issues (in one "
					+ "of the project's resolved states) and the per-workflow-state issue counts.")
	public MetricsView getProjectMetrics(
			@McpToolParam(description = "Project id or key (e.g. ASTA)") String idOrKey) {
		scopeGuard.require(Scopes.PROJECTS_READ);
		User user = currentUser.require();
		Project project = accessibleProject(idOrKey, user);
		long total = issues.countByProjectId(project.getId());
		long resolved = issues.countByProjectIdAndStateIn(project.getId(), project.getResolvedStates());
		return new MetricsView(project.getId(), project.getKey(), total, resolved,
				total - resolved, projectService.stateUsage(project));
	}

	/** Aggregate issue counts for a project, overall and per workflow state. */
	public record MetricsView(String projectId, String projectKey, long totalIssues,
			long resolvedIssues, long openIssues, Map<String, Long> issuesByState) {
	}

	private Project accessibleProject(String idOrKey, User user) {
		Project project = projects.findById(idOrKey)
				.or(() -> projects.findByKeyIgnoreCase(idOrKey))
				.orElseThrow(() -> ApiException.notFound("project"));
		projectService.assertMember(project, user);
		return project;
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
