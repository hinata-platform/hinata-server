package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.mcp.McpViews.IssueView;
import com.ahmadre.hinata.mcp.McpViews.PageResult;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only MCP tools over the issue tracker. Every tool gates on the
 * {@code issues:read} scope, resolves the caller via {@link CurrentUser} and
 * delegates to {@link IssueService} so the service-layer project ACLs apply —
 * the model can never see an issue its caller could not see in the app. Results
 * are the lean {@link McpViews} projections, never raw entities.
 */
@Service
@RequiredArgsConstructor
public class IssueReadTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final IssueService issueService;

	@McpTool(name = "search_issues", title = "Search issues",
			description = "Search and filter issues the caller can access. All filters are "
					+ "optional; results are paginated (newest first). projectId/state/"
					+ "assigneeId/sprintId/type are exact matches, query is a free-text match "
					+ "on title or readable id (e.g. ASTA-42).")
	public PageResult<IssueView> searchIssues(
			@McpToolParam(required = false, description = "Restrict to this project id") String projectId,
			@McpToolParam(required = false, description = "Workflow state name, e.g. 'In Progress'") String state,
			@McpToolParam(required = false, description = "User id of an assignee") String assigneeId,
			@McpToolParam(required = false, description = "Sprint id") String sprintId,
			@McpToolParam(required = false, description = "Issue type: EPIC, STORY, TASK, BUG, FEATURE, SUBTASK") String type,
			@McpToolParam(required = false, description = "Free-text query over title / readable id") String query,
			@McpToolParam(required = false, description = "Only issues not in any sprint (backlog)") Boolean noSprint,
			@McpToolParam(required = false, description = "Zero-based page index (default 0)") Integer page,
			@McpToolParam(required = false, description = "Page size, max 100 (default 25)") Integer size) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		Page<Issue> result = issueService.search(projectId, state, assigneeId, sprintId, type, query,
				Boolean.TRUE.equals(noSprint), pageOr(page), sizeOr(size), user);
		return PageResult.of(result, IssueView::of);
	}

	@McpTool(name = "list_my_issues", title = "List my issues",
			description = "List issues assigned to the current caller, paginated (newest first).")
	public PageResult<IssueView> listMyIssues(
			@McpToolParam(required = false, description = "Zero-based page index (default 0)") Integer page,
			@McpToolParam(required = false, description = "Page size, max 100 (default 25)") Integer size) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		Page<Issue> result = issueService.search(null, null, user.getId(), null, null, null,
				false, pageOr(page), sizeOr(size), user);
		return PageResult.of(result, IssueView::of);
	}

	@McpTool(name = "get_issue", title = "Get an issue",
			description = "Fetch a single issue by its canonical id or its readable id "
					+ "(e.g. ASTA-42). Fails if the caller is not a member of the issue's project.")
	public IssueView getIssue(
			@McpToolParam(description = "Issue id or readable id (e.g. ASTA-42)") String idOrReadableId) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		return IssueView.of(issueService.getForUser(idOrReadableId, user));
	}

	@McpTool(name = "get_issue_hierarchy", title = "Get an issue's hierarchy",
			description = "Fetch an issue's breadcrumb ancestors (root -> immediate parent) "
					+ "and its direct children, by id or readable id (e.g. ASTA-42).")
	public HierarchyView getIssueHierarchy(
			@McpToolParam(description = "Issue id or readable id (e.g. ASTA-42)") String idOrReadableId) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		IssueService.Hierarchy hierarchy = issueService.hierarchyOf(idOrReadableId, user);
		return new HierarchyView(
				hierarchy.ancestors().stream().map(IssueView::of).toList(),
				hierarchy.children().stream().map(IssueView::of).toList());
	}

	/** Breadcrumb ancestors (root first) plus the direct children of an issue. */
	public record HierarchyView(List<IssueView> ancestors, List<IssueView> children) {
	}

	private static int pageOr(Integer page) {
		return page == null || page < 0 ? 0 : page;
	}

	private static int sizeOr(Integer size) {
		return size == null || size <= 0 ? 25 : size;
	}
}
