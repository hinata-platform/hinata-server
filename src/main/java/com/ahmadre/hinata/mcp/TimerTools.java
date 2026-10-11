package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.RunningTimer;
import com.ahmadre.hinata.timetracking.TimeTag;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.timetracking.TimerController;
import com.ahmadre.hinata.timetracking.TimerService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * MCP tools for the caller's own running timer (HIN-97): read it, start one, stop it into an
 * entry, throw it away.
 *
 * <p>Every call goes through {@link TimerService}, the service the app's timer routes use, so a
 * timer started here is the same document the app shows ticking, with the same one-per-person
 * rule and the same required fields at the stop. What this class adds is what the HTTP side gets
 * from elsewhere: the scope gate (timers are writes, {@code worklog:write}; reading is
 * {@code worklog:read}), the module flag the REST interceptor would otherwise check, the length
 * limits Bean Validation enforces there, and a refusal an agent can read.
 *
 * <p>There is no foreign timer to reach: every method resolves the caller's own, and there is no
 * parameter that names a person.
 */
@Service
@RequiredArgsConstructor
public class TimerTools {

	private final TimerService timers;
	private final TimeTrackingSettings settings;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final McpErrors errors;
	private final AuditService audit;

	/**
	 * A running timer for MCP callers. No elapsed time, for the same reason the app's response
	 * carries none: it is {@code now - startedAt}, and a figure frozen into the answer is wrong a
	 * second later.
	 */
	public record TimerView(String id, Instant startedAt, String projectId, String issueId,
			String description, String activityType, List<String> tags, boolean billable,
			String mode, Integer plannedMinutes, String phase) {

		static TimerView of(RunningTimer timer) {
			return new TimerView(timer.getId(), timer.getStartedAt(), timer.getProjectId(),
					timer.getIssueId(), timer.getDescription(), timer.getActivityType(),
					timer.getTags() == null ? List.of() : timer.getTags(), timer.isBillable(),
					timer.getMode() == null ? RunningTimer.Mode.STOPWATCH.name() : timer.getMode().name(),
					timer.getPlannedMinutes(), timer.getPhase() == null ? null : timer.getPhase().name());
		}
	}

	/** Whether a timer runs, and which. {@code timer} is null when none does. */
	public record TimerStatus(boolean running, TimerView timer) {
	}

	@McpTool(name = "get_timer", title = "Get my timer",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's running timer, if one runs: when it started, what it is "
					+ "filed under and how it counts. running=false when no timer runs. Elapsed time "
					+ "is now minus startedAt.")
	public TimerStatus get_timer() {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.advancedEnabled(), me);
		return status(me);
	}

	@McpTool(name = "start_timer", title = "Start a timer",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false),
			description = "Start a timer for the caller, optionally filed under a project or an "
					+ "issue (id or readable id, e.g. HIN-42). A person has at most one timer: if one "
					+ "is already running this fails; stop or discard it first. mode is STOPWATCH "
					+ "(default), COUNTDOWN or POMODORO. Returns the running timer.")
	public TimerView start_timer(
			@McpToolParam(required = false, description = "What the work is (at most 2000 characters)") String description,
			@McpToolParam(required = false, description = "Project id to file the time under") String projectId,
			@McpToolParam(required = false, description = "Issue id or readable id (e.g. HIN-42); supplies its project") String issueId,
			@McpToolParam(required = false, description = "Activity type, e.g. Development, Meeting") String activityType,
			@McpToolParam(required = false, description = "Tags (at most 20)") List<String> tags,
			@McpToolParam(required = false, description = "Whether the time is billable; the server default when omitted") Boolean billable,
			@McpToolParam(required = false, description = "STOPWATCH, COUNTDOWN or POMODORO; STOPWATCH when omitted") String mode) {
		User me = caller(Scopes.WORKLOG_WRITE);
		errors.requireEnabled(settings.advancedEnabled(), me);
		requireContent(description, activityType, tags, me);
		RunningTimer.Mode counting = modeOf(mode, me);
		RunningTimer started = errors.readable(me, () -> timers.start(
				new TimerService.StartDraft(
						new TimerService.TimerDraft(projectId, issueId, description, activityType, tags, billable),
						counting, null, null),
				me));
		audit.event(AuditAction.MCP_TIMER_STARTED).actor(me).target(me)
				.meta("timer", started.getId()).log();
		return TimerView.of(started);
	}

	@McpTool(name = "stop_timer", title = "Stop my timer",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Stop the caller's running timer and file it as a work item. The fields "
					+ "given here fill in or replace what the timer carried; the rest is kept. Fails "
					+ "with a clear message when no timer runs, and when the server requires a field "
					+ "(project, issue, description, tag) the timer still lacks: the timer then keeps "
					+ "running. Returns the filed work item.")
	public TimeTrackingTools.WorkItemView stop_timer(
			@McpToolParam(required = false, description = "What the work was (at most 2000 characters)") String description,
			@McpToolParam(required = false, description = "Project id to file the time under") String projectId,
			@McpToolParam(required = false, description = "Issue id or readable id (e.g. HIN-42)") String issueId) {
		User me = caller(Scopes.WORKLOG_WRITE);
		errors.requireEnabled(settings.advancedEnabled(), me);
		errors.requireLength(description, TimerController.MAX_DESCRIPTION, "description", me);
		// Without a timer id, the way a person presses stop: there is one timer per person, and an
		// agent that retries a stop which already went through is told so rather than handed the
		// entry of a timer it may have started since.
		TimerService.Stopped stopped = errors.readable(me, () -> timers.stop(
				new TimerService.StopRequest(null, null, projectId, issueId, description, null, null, null),
				me));
		audit.event(AuditAction.MCP_TIMER_STOPPED).actor(me).target(me)
				.meta("workItem", stopped.entry().getId())
				.meta("minutes", String.valueOf(stopped.entry().getDurationMinutes())).log();
		return TimeTrackingTools.WorkItemView.of(stopped.entry());
	}

	@McpTool(name = "discard_timer", title = "Discard my timer",
			annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false),
			description = "Throw the caller's running timer away without recording anything. "
					+ "Fails when no timer runs. This cannot be undone.")
	public String discard_timer() {
		User me = caller(Scopes.WORKLOG_WRITE);
		errors.requireEnabled(settings.advancedEnabled(), me);
		errors.readable(me, () -> timers.discard(me));
		audit.event(AuditAction.MCP_TIMER_DISCARDED).actor(me).target(me).log();
		return "discarded";
	}

	/** The running timer as markdown, for the {@code hinata://time/timer} resource. */
	String timerMarkdown() {
		User me = caller(Scopes.WORKLOG_READ);
		errors.requireEnabled(settings.advancedEnabled(), me);
		TimerStatus status = status(me);
		if (!status.running()) {
			return "# Timer\n\nNo timer is running.\n";
		}
		TimerView timer = status.timer();
		StringBuilder md = new StringBuilder("# Timer\n\n");
		md.append("- Started: ").append(timer.startedAt()).append('\n');
		md.append("- Mode: ").append(timer.mode()).append('\n');
		if (timer.projectId() != null) md.append("- Project: ").append(timer.projectId()).append('\n');
		if (timer.issueId() != null) md.append("- Issue: ").append(timer.issueId()).append('\n');
		if (timer.activityType() != null) md.append("- Activity: ").append(timer.activityType()).append('\n');
		if (!timer.tags().isEmpty()) md.append("- Tags: ").append(String.join(", ", timer.tags())).append('\n');
		md.append("- Billable: ").append(timer.billable()).append('\n');
		if (timer.description() != null && !timer.description().isBlank()) {
			md.append('\n').append(timer.description()).append('\n');
		}
		return md.toString();
	}

	private TimerStatus status(User me) {
		return timers.current(me)
				.map(timer -> new TimerStatus(true, TimerView.of(timer)))
				.orElseGet(() -> new TimerStatus(false, null));
	}

	private void requireContent(String description, String activityType, List<String> tags, User me) {
		errors.requireLength(description, TimerController.MAX_DESCRIPTION, "description", me);
		errors.requireLength(activityType, TimerController.MAX_ACTIVITY, "activityType", me);
		errors.requireValues(tags, TimerController.MAX_TAGS, TimeTag.MAX_NAME, "tags", me);
	}

	private RunningTimer.Mode modeOf(String mode, User me) {
		if (mode == null || mode.isBlank()) {
			return RunningTimer.Mode.STOPWATCH;
		}
		try {
			return RunningTimer.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw errors.badValue("mode", me);
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
