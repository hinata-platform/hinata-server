package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP write tool for time tracking. Mirrors {@code TimeTrackingController.add}:
 * logs a work item against an issue for the authenticated user. Gates on the
 * {@code worklog:write} scope, audits the write and returns a lean work-item view.
 */
@Service
@RequiredArgsConstructor
public class TimeTrackingTools {

	private final TimeTrackingService timeTracking;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final AuditService audit;
	// The time-tracking service itself does not gate on project membership, so
	// the MCP tools resolve the issue through IssueService first — same ACL as
	// every other issue-facing tool.
	private final IssueService issueService;
	private final WorkItemRepository workItems;

	/** Lean projection of a logged work item for MCP callers. */
	public record WorkItemView(String id, String issueId, String projectId, String userId,
			LocalDate date, int durationMinutes, String activityType, String description,
			Instant createdAt) {

		static WorkItemView of(WorkItem item) {
			return new WorkItemView(item.getId(), item.getIssueId(), item.getProjectId(),
					item.getUserId(), item.getDate(), item.getDurationMinutes(),
					item.getActivityType(), item.getDescription(), item.getCreatedAt());
		}
	}

	@McpTool(name = "log_work", title = "Log work",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false),
			description = "Log time spent on an issue for the current user. Duration is in minutes "
					+ "(1-1440). Date defaults to today (UTC) and activity type to Development when omitted. "
					+ "Returns the created work item.")
	public WorkItemView log_work(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42) to log work against") String issueId,
			@McpToolParam(required = true, description = "Minutes spent (1-1440)") int minutes,
			@McpToolParam(required = false, description = "Date the work was done (yyyy-MM-dd); defaults to today (UTC)") LocalDate date,
			@McpToolParam(required = false, description = "Activity type, e.g. Development, Testing, Documentation, Meeting; defaults to Development") String activityType,
			@McpToolParam(required = false, description = "Free-text note describing the work") String description) {
		scopeGuard.require(Scopes.WORKLOG_WRITE);
		User me = currentUser.require();
		Issue issue = issueService.getForUser(issueId, me);
		WorkItem item = WorkItem.builder()
				.userId(me.getId())
				.durationMinutes(minutes)
				.date(date)
				.activityType(activityType != null ? activityType : "Development")
				.description(description)
				.build();
		WorkItem saved = timeTracking.add(issue.getId(), item);
		audit.event(AuditAction.MCP_WORK_LOGGED).actor(me)
				.meta("issue", saved.getIssueId())
				.meta("minutes", String.valueOf(saved.getDurationMinutes())).log();
		return WorkItemView.of(saved);
	}

	@McpTool(name = "list_work_items", title = "List work items",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the work items (logged time) of an issue, newest first, by issue "
					+ "id or readable id (e.g. HIN-42).")
	public List<WorkItemView> listWorkItems(
			@McpToolParam(description = "Issue id or readable id (e.g. HIN-42)") String issueId) {
		scopeGuard.require(Scopes.WORKLOG_READ);
		User me = currentUser.require();
		Issue issue = issueService.getForUser(issueId, me);
		return workItems.findByIssueIdOrderByDateDesc(issue.getId()).stream()
				.map(WorkItemView::of).toList();
	}

	@McpTool(name = "my_timesheet", title = "My timesheet",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's own logged time between two dates (at most 92 days apart), "
					+ "grouped per project with minutes per day and a total.")
	public List<TimeTrackingService.TimesheetRow> myTimesheet(
			@McpToolParam(required = true, description = "First day, inclusive (yyyy-MM-dd)") LocalDate from,
			@McpToolParam(required = true, description = "Last day, inclusive (yyyy-MM-dd)") LocalDate to,
			@McpToolParam(required = false, description = "Only time logged on this project") String projectId) {
		scopeGuard.require(Scopes.WORKLOG_READ);
		User me = currentUser.require();
		// Always self-scoped: an MCP caller can never inspect another user's time.
		return timeTracking.timesheet(from, to, me.getId(), projectId);
	}

	@McpTool(name = "delete_work_item", title = "Delete work item",
			annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false),
			description = "Delete one of the caller's own work items (logged time). This cannot "
					+ "be undone.")
	public String delete_work_item(
			@McpToolParam(required = true, description = "Id of the work item to delete") String workItemId) {
		scopeGuard.require(Scopes.WORKLOG_WRITE);
		User me = currentUser.require();
		// Never pass admin=true here: MCP deletion stays strictly owner-scoped.
		timeTracking.delete(workItemId, me.getId(), false);
		audit.event(AuditAction.MCP_WORK_DELETED).actor(me)
				.meta("workItem", workItemId).log();
		return "deleted";
	}
}
