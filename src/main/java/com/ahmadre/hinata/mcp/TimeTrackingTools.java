package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

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
		WorkItem item = WorkItem.builder()
				.userId(me.getId())
				.durationMinutes(minutes)
				.date(date)
				.activityType(activityType != null ? activityType : "Development")
				.description(description)
				.build();
		WorkItem saved = timeTracking.add(issueId, item);
		audit.event(AuditAction.MCP_WORK_LOGGED).actor(me)
				.meta("issue", saved.getIssueId())
				.meta("minutes", String.valueOf(saved.getDurationMinutes())).log();
		return WorkItemView.of(saved);
	}
}
