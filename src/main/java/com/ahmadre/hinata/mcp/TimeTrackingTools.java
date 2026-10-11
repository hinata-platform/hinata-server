package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.TimeReportFilter;
import com.ahmadre.hinata.timetracking.TimeReportQuery;
import com.ahmadre.hinata.timetracking.TimeReportService;
import com.ahmadre.hinata.timetracking.TimeTag;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.timetracking.TimerController;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * MCP tools for time tracking. Mirror the REST endpoints: log a work item
 * against an issue for the authenticated user, list an issue's items, read the
 * caller's own timesheet, delete one of the caller's own items. Every call goes
 * through {@link TimeTrackingService}, which applies the same project-membership
 * rule as the REST API; the tools add the scope gate and the audit entry.
 *
 * <p>The three tools of HIN-97 — {@code list_my_work_items}, {@code update_work_item} and
 * {@code time_summary} — belong to the extended module, so each asks
 * {@link TimeTrackingSettings#advancedEnabled()} itself: MCP does not pass through the HTTP gate
 * that answers for the REST routes. All three are the caller's own and nobody else's, with no
 * parameter that could name another person. The four older tools are unchanged, flag and all —
 * the published app and existing clients use them with the module off.
 */
@Service
@RequiredArgsConstructor
public class TimeTrackingTools {

	/** Most groups one summary answers with; a person's own hours rarely need a second page. */
	static final int SUMMARY_GROUPS_MAX = 100;

	/** Longest description search, as the report filter bounds it. */
	static final int QUERY_MAX = TimeReportFilter.MAX_DESCRIPTION;

	private final TimeTrackingService timeTracking;
	private final TimeReportService reports;
	private final TimeTrackingSettings settings;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final McpErrors errors;
	private final AuditService audit;

	/**
	 * Lean projection of a logged work item for MCP callers. {@code hidden} marks
	 * somebody else's entry whose details the caller may not read: it carries the day,
	 * the duration and the activity, and nothing about who worked or what they wrote.
	 */
	public record WorkItemView(String id, String issueId, String projectId, String userId,
			LocalDate date, int durationMinutes, String activityType, String description,
			Instant createdAt, Instant startedAt, Instant endedAt, boolean billable,
			List<String> tags, String source, Instant updatedAt, String updatedBy,
			String sharedFromId, String importId, WorkItem.CalendarRef calendarRef, boolean hidden,
			String invoiceId) {

		static WorkItemView of(WorkItem item) {
			return of(item, true);
		}

		static WorkItemView of(WorkItem item, boolean readsDetails) {
			if (!readsDetails) {
				return new WorkItemView(item.getId(), item.getIssueId(), item.getProjectId(), null,
						item.getDate(), item.getDurationMinutes(), item.getActivityType(), null, null,
						null, null, item.isBillable(), List.of(), item.getSource().name(), null, null,
						null, null, null, true, item.getInvoiceId());
			}
			return new WorkItemView(item.getId(), item.getIssueId(), item.getProjectId(),
					item.getUserId(), item.getDate(), item.getDurationMinutes(),
					item.getActivityType(), item.getDescription(), item.getCreatedAt(),
					item.getStartedAt(), item.getEndedAt(), item.isBillable(), item.getTags(),
					item.getSource().name(), item.getUpdatedAt(), item.getUpdatedBy(),
					item.getSharedFromId(), item.getImportId(), item.getCalendarRef(), false,
					item.getInvoiceId());
		}
	}

	@McpTool(name = "log_work", title = "Log work",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false),
			description = "Log time spent on an issue for the current user. Duration is in minutes "
					+ "(1-1440). Date defaults to today in the user's time zone, must not be in the "
					+ "future and at most 365 days back; activity type defaults to Development when "
					+ "omitted. Returns the created work item.")
	public WorkItemView log_work(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42) to log work against") String issueId,
			@McpToolParam(required = true, description = "Minutes spent (1-1440)") int minutes,
			@McpToolParam(required = false, description = "Date the work was done (yyyy-MM-dd); defaults to today in the user's time zone") LocalDate date,
			@McpToolParam(required = false, description = "Activity type, e.g. Development, Testing, Documentation, Meeting; defaults to Development") String activityType,
			@McpToolParam(required = false, description = "Free-text note describing the work") String description) {
		scopeGuard.require(Scopes.WORKLOG_WRITE);
		User me = currentUser.require();
		WorkItem saved = timeTracking.add(issueId,
				new TimeTrackingService.NewWorkItem(minutes, date, activityType, description,
						null, null, null, null),
				WorkItem.Source.MCP, me);
		audit.event(AuditAction.MCP_WORK_LOGGED).actor(me)
				.meta("issue", saved.getIssueId())
				.meta("minutes", String.valueOf(saved.getDurationMinutes())).log();
		return WorkItemView.of(saved);
	}

	@McpTool(name = "list_work_items", title = "List work items",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the work items (logged time) of an issue, newest first (at most "
					+ "the 200 newest), by issue id or readable id (e.g. HIN-42). Other people's "
					+ "items may come back with hidden=true: day, duration and activity only.")
	public List<WorkItemView> listWorkItems(
			@McpToolParam(description = "Issue id or readable id (e.g. HIN-42)") String issueId) {
		scopeGuard.require(Scopes.WORKLOG_READ);
		User me = currentUser.require();
		// The same rule as the REST list: a token never reads more than its holder may.
		Predicate<WorkItem> readable = timeTracking.detailsVisibleTo(me);
		return timeTracking.list(issueId, me).stream()
				.map(item -> WorkItemView.of(item, readable.test(item))).toList();
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
		return timeTracking.timesheet(from, to, me.getId(), projectId, me);
	}

	@McpTool(name = "delete_work_item", title = "Delete work item",
			annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false),
			description = "Delete one of the caller's own work items (logged time). This cannot "
					+ "be undone.")
	public String delete_work_item(
			@McpToolParam(required = true, description = "Id of the work item to delete") String workItemId) {
		scopeGuard.require(Scopes.WORKLOG_WRITE);
		User me = currentUser.require();
		// Owner-only on purpose: a token never gets the lead/admin elevation the app has.
		timeTracking.deleteOwn(workItemId, me);
		audit.event(AuditAction.MCP_WORK_DELETED).actor(me)
				.meta("workItem", workItemId).log();
		return "deleted";
	}

	// --- HIN-97: the caller's own entries, edited and summed ---------------------

	@McpTool(name = "list_my_work_items", title = "List my work items",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's own work items between two dates, newest first, one page "
					+ "at a time (size at most 100, default 50). Optionally only one project, or only "
					+ "entries whose description contains q. Never anybody else's entries.")
	public McpViews.PageResult<WorkItemView> list_my_work_items(
			@McpToolParam(required = true, description = "First day, inclusive (yyyy-MM-dd)") LocalDate from,
			@McpToolParam(required = true, description = "Last day, inclusive (yyyy-MM-dd)") LocalDate to,
			@McpToolParam(required = false, description = "Only entries filed under this project id") String projectId,
			@McpToolParam(required = false, description = "Only entries whose description contains this text (at most 100 characters)") String q,
			@McpToolParam(required = false, description = "Zero-based page index (default 0)") Integer page,
			@McpToolParam(required = false, description = "Page size, max 100 (default 50)") Integer size) {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.advancedEnabled(), me);
		errors.requireLength(q, QUERY_MAX, "q", me);
		// The service clamps page and size; it also keeps the list the caller's own, whatever the
		// filter says, which is the guarantee here.
		return errors.readable(me, () -> McpViews.PageResult.of(
				timeTracking.entries(new TimeTrackingService.EntryFilter(from, to, projectId, q),
						page == null ? 0 : page, size == null ? 50 : size, me),
				WorkItemView::of));
	}

	@McpTool(name = "update_work_item", title = "Update work item",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Change one of the caller's own work items. Only the fields given change. "
					+ "A start and end time replace the duration; a duration alone keeps no interval. "
					+ "Refused on a locked day, inside a submitted or approved timesheet period, or "
					+ "for an invoiced entry; the message says why and what would free it. Where an "
					+ "entry is filed (project, issue) is not changed here. Returns the updated item.")
	public WorkItemView update_work_item(
			@McpToolParam(required = true, description = "Id of the caller's own work item") String id,
			@McpToolParam(required = false, description = "Minutes (1-1440)") Integer durationMinutes,
			@McpToolParam(required = false, description = "Day the work was done (yyyy-MM-dd)") LocalDate date,
			@McpToolParam(required = false, description = "Start time (ISO-8601 instant, e.g. 2026-10-11T08:00:00Z)") Instant startedAt,
			@McpToolParam(required = false, description = "End time (ISO-8601 instant)") Instant endedAt,
			@McpToolParam(required = false, description = "What the work was (at most 2000 characters)") String description,
			@McpToolParam(required = false, description = "Activity type, e.g. Development, Meeting") String activityType,
			@McpToolParam(required = false, description = "Replacement tags (at most 20)") List<String> tags,
			@McpToolParam(required = false, description = "Whether the time is billable") Boolean billable) {
		User me = caller(Scopes.WORKLOG_WRITE);
		errors.requireEnabled(settings.advancedEnabled(), me);
		errors.requireRange(durationMinutes, 1, TimeTrackingService.MAX_MINUTES, "durationMinutes", me);
		errors.requireLength(description, TimerController.MAX_DESCRIPTION, "description", me);
		errors.requireLength(activityType, TimerController.MAX_ACTIVITY, "activityType", me);
		errors.requireValues(tags, TimerController.MAX_TAGS, TimeTag.MAX_NAME, "tags", me);
		WorkItem saved = errors.readable(me, () -> {
			// Owner-only, as delete_work_item is: a token never edits as the lead or admin its holder
			// may be. requireOwn answers a colleague's id exactly as a missing one, so the id space
			// cannot be walked for which entries exist.
			timeTracking.requireOwn(id, me);
			return timeTracking.update(id, new TimeTrackingService.WorkItemPatch(durationMinutes, date,
					activityType, description, startedAt != null, startedAt, endedAt != null, endedAt,
					tags, billable), me);
		});
		audit.event(AuditAction.MCP_WORK_UPDATED).actor(me)
				.meta("workItem", saved.getId())
				.meta("minutes", String.valueOf(saved.getDurationMinutes())).log();
		return WorkItemView.of(saved);
	}

	/** A summary of the caller's own time: the totals, and at most a hundred groups. */
	public record TimeSummaryView(LocalDate from, LocalDate to, String groupBy,
			TimeReportService.Totals totals, List<TimeReportService.Group> groups, long groupCount,
			boolean truncated) {
	}

	@McpTool(name = "time_summary", title = "Summarise my time",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's own time between two dates (at most 366 days), totalled and "
					+ "grouped by PROJECT (default), ISSUE, ACTIVITY, TAG, DAY, WEEK or MONTH; at most "
					+ "100 groups, largest first (time buckets in date order), truncated=true when "
					+ "there were more. rounding (NONE, UP, DOWN, NEAREST) with roundingIncrement in "
					+ "minutes overrides the server's rounding; minutes are rounded per entry, "
					+ "filedMinutes are as logged. Only the caller's own entries, never a team's.")
	public TimeSummaryView time_summary(
			@McpToolParam(required = true, description = "First day, inclusive (yyyy-MM-dd)") LocalDate from,
			@McpToolParam(required = true, description = "Last day, inclusive (yyyy-MM-dd)") LocalDate to,
			@McpToolParam(required = false, description = "PROJECT, ISSUE, ACTIVITY, TAG, DAY, WEEK or MONTH; PROJECT when omitted") String groupBy,
			@McpToolParam(required = false, description = "Only time filed under this project id") String projectId,
			@McpToolParam(required = false, description = "NONE, UP, DOWN or NEAREST; the server's setting when omitted") String rounding,
			@McpToolParam(required = false, description = "Rounding increment in minutes (1-240, default 1)") Integer roundingIncrement) {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.advancedEnabled(), me);
		TimeReportService.GroupBy grouping = groupingOf(groupBy, me);
		TimePolicy.Rounding mode = roundingOf(rounding, me);
		// Self-scoped by naming the caller as the only person, which is also what keeps the report
		// on the entries rather than on a project's aggregate: the summary of somebody's own hours
		// never contains a colleague's minutes, whatever the caller may see in the app.
		TimeReportQuery query = new TimeReportQuery(from, to,
				projectId == null || projectId.isBlank() ? null : List.of(projectId), List.of(me.getId()),
				null, null, null, null, null, null, mode, roundingIncrement, null);
		TimeReportService.Summary summary = errors.readable(me, () ->
				reports.summary(me, reports.filter(me, query), grouping, 0, SUMMARY_GROUPS_MAX));
		return new TimeSummaryView(from, to, grouping.name(), summary.totals(),
				summary.groups().getContent(), summary.groups().getTotalElements(),
				summary.groups().getTotalElements() > summary.groups().getContent().size());
	}

	/** One of the caller's own entries as markdown, for {@code hinata://time/entries/{id}}. */
	String entryMarkdown(String id) {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.advancedEnabled(), me);
		WorkItem item = errors.readable(me, () -> timeTracking.requireOwn(id, me));
		StringBuilder md = new StringBuilder("# Work item ").append(item.getId()).append("\n\n");
		md.append("- Date: ").append(item.getDate()).append('\n');
		md.append("- Minutes: ").append(item.getDurationMinutes()).append('\n');
		if (item.getStartedAt() != null && item.getEndedAt() != null) {
			md.append("- From: ").append(item.getStartedAt()).append(" to ").append(item.getEndedAt()).append('\n');
		}
		if (item.getProjectId() != null) md.append("- Project: ").append(item.getProjectId()).append('\n');
		if (item.getIssueId() != null) md.append("- Issue: ").append(item.getIssueId()).append('\n');
		if (item.getActivityType() != null) md.append("- Activity: ").append(item.getActivityType()).append('\n');
		if (item.getTags() != null && !item.getTags().isEmpty()) {
			md.append("- Tags: ").append(String.join(", ", item.getTags())).append('\n');
		}
		md.append("- Billable: ").append(item.isBillable()).append('\n');
		if (item.getInvoiceId() != null) md.append("- Invoice: ").append(item.getInvoiceId()).append('\n');
		if (item.getDescription() != null && !item.getDescription().isBlank()) {
			md.append('\n').append(item.getDescription()).append('\n');
		}
		return md.toString();
	}

	private TimeReportService.GroupBy groupingOf(String groupBy, User me) {
		if (groupBy == null || groupBy.isBlank()) {
			return TimeReportService.GroupBy.PROJECT;
		}
		TimeReportService.GroupBy grouping = enumOf(TimeReportService.GroupBy.class, groupBy, "groupBy", me);
		// By person or by team is a report about other people. A token reads its holder's own time
		// and nothing that ranks or compares colleagues.
		if (grouping == TimeReportService.GroupBy.USER || grouping == TimeReportService.GroupBy.TEAM) {
			throw errors.badValue("groupBy", me);
		}
		return grouping;
	}

	private TimePolicy.Rounding roundingOf(String rounding, User me) {
		return rounding == null || rounding.isBlank() ? null
				: enumOf(TimePolicy.Rounding.class, rounding, "rounding", me);
	}

	private <E extends Enum<E>> E enumOf(Class<E> type, String value, String field, User me) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw errors.badValue(field, me);
		}
	}

	/**
	 * The caller, once the token has shown [scope]. The refusal goes through {@link McpErrors} like
	 * every other one here, so an agent reads which scope is missing rather than a message key.
	 */
	private User caller(String scope) {
		User me = currentUser.require();
		errors.readable(me, () -> scopeGuard.require(scope));
		return me;
	}
}
