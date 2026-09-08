package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The caller's running timer.
 *
 * <p>One resource, not a collection: a person has at most one timer, so it has
 * no id in its URL and {@code GET} answers 204 when there is none. That shape is
 * what lets a client reconcile with a single unconditional request on
 * reconnect — which it has to do, because the {@code timer} event on
 * {@code /me/stream} is best-effort and scoped to one application instance.
 *
 * <p>Behind {@link AdvancedTimeTrackingGate}: with the module off every route
 * here is 404 {@code error.feature.disabled}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/me/timer")
@RequiredArgsConstructor
public class TimerController {

	private final TimerService timers;
	// For the overlap advice on the entry a stop produced — the same answer the
	// manual routes give, from the same place.
	private final TimeTrackingService timeTracking;
	private final CurrentUser currentUser;

	// --- DTOs -----------------------------------------------------------------

	/**
	 * A running timer on the wire. It carries no elapsed time: the client counts
	 * from {@code startedAt} against its own clock, so the number on screen keeps
	 * moving between requests instead of freezing until the next one.
	 */
	public record TimerResponse(String id, Instant startedAt, String projectId, String issueId,
			String description, String activityType, List<String> tags, boolean billable,
			RunningTimer.Mode mode, Integer plannedMinutes, RunningTimer.Phase phase,
			Instant phaseStartedAt, RunningTimer.Pomodoro pomodoro, int cyclesDone) {

		public static TimerResponse from(RunningTimer timer) {
			return new TimerResponse(timer.getId(), timer.getStartedAt(), timer.getProjectId(),
					timer.getIssueId(), timer.getDescription(), timer.getActivityType(),
					timer.getTags(), timer.isBillable(), timer.getMode(), timer.getPlannedMinutes(),
					timer.getPhase(), timer.getPhaseStartedAt(), timer.getPomodoro(),
					timer.getCyclesDone());
		}
	}

	/**
	 * What a client says when starting a timer, or when changing a running one.
	 *
	 * <p>On {@code PATCH} this is the timer's whole editable state, not a diff:
	 * a field left out is cleared. See {@link TimerService#patch} for why a
	 * single resource with five fields is treated that way.
	 */
	/**
	 * The limits every one of these records states, in one place.
	 *
	 * <p>Three records carry the same six fields — start, patch and stop — and an
	 * annotation is a constant expression, so the only way to keep them in step
	 * is to name the numbers. Raising the description limit on one route and
	 * silently keeping the old one on the others is otherwise a one-line mistake.
	 */
	public static final int MAX_DESCRIPTION = 2000;
	public static final int MAX_ACTIVITY = 60;
	public static final int MAX_TAGS = 20;
	public static final int MAX_TAG = 40;

	public record TimerRequest(
			String projectId,
			String issueId,
			@Size(max = MAX_DESCRIPTION) String description,
			@Size(max = MAX_ACTIVITY) String activityType,
			@Size(max = MAX_TAGS) List<@Size(max = MAX_TAG) String> tags,
			Boolean billable) {

		TimerService.TimerDraft toDraft() {
			return new TimerService.TimerDraft(projectId, issueId, description, activityType, tags,
					billable);
		}
	}

	/**
	 * Starting one: everything {@link TimerRequest} says, plus how it is to count.
	 *
	 * <p>The mode lives here and not on {@code PATCH} deliberately. A patch
	 * replaces the timer's whole editable state, so a mode field there would be
	 * cleared by every rename — a pomodoro would turn back into a stopwatch the
	 * first time somebody typed a description. How a timer counts is settled when
	 * it starts; what it is called is not.
	 *
	 * <p>Every number is optional and every one is clamped rather than refused:
	 * these come from a preferences panel, and the nearest usable length beats a
	 * 400 in the middle of somebody deciding to start working.
	 */
	public record StartRequest(
			String projectId,
			String issueId,
			@Size(max = MAX_DESCRIPTION) String description,
			@Size(max = MAX_ACTIVITY) String activityType,
			@Size(max = MAX_TAGS) List<@Size(max = MAX_TAG) String> tags,
			Boolean billable,
			RunningTimer.Mode mode,
			Integer plannedMinutes,
			PomodoroRequest pomodoro) {

		TimerService.StartDraft toDraft() {
			return new TimerService.StartDraft(
					new TimerService.TimerDraft(projectId, issueId, description, activityType, tags,
							billable),
					mode, plannedMinutes, pomodoro == null ? null : pomodoro.toConfig());
		}
	}

	/** The lengths one pomodoro run counts by. */
	public record PomodoroRequest(Integer work, Integer shortBreak, Integer longBreak,
			Integer cycles) {

		RunningTimer.Pomodoro toConfig() {
			// Zero for an absent value, which is what the service reads as "not
			// given" — the alternative is four more Integer fields carried through
			// a document that has no use for the distinction.
			return RunningTimer.Pomodoro.builder()
					.work(work == null ? 0 : work)
					.shortBreak(shortBreak == null ? 0 : shortBreak)
					.longBreak(longBreak == null ? 0 : longBreak)
					.cycles(cycles == null ? 0 : cycles)
					.build();
		}
	}

	/**
	 * Ending one pomodoro phase and beginning the next.
	 *
	 * <p>{@code timerId} is the idempotency token, exactly as on {@code stop}: a
	 * retried request that arrives after the phase has already turned answers with
	 * the phase that is running instead of skipping the next one. Required, unlike
	 * on {@code stop} — there it is worth sending and here it is the whole
	 * guarantee, and a caller that omitted it would advance whatever happened to
	 * be running rather than the phase it meant.
	 */
	public record PhaseRequest(@NotBlank String timerId) {
	}

	/**
	 * Stopping. Every field is optional: a timer is meant to be started first and
	 * described afterwards, so this is where the description and the placement
	 * usually arrive. {@code endedAt} lets a client that lost connectivity report
	 * when the work actually stopped rather than when the request got through.
	 *
	 * <p>An absent field is <em>kept</em> here, which is the opposite of
	 * {@link TimerRequest} on {@code PATCH} — and deliberately so. A patch
	 * replaces what the timer says about itself; a stop describes what happened,
	 * and everything it does not mention already happened the way the timer
	 * recorded it.
	 */
	public record StopRequest(
			/**
			 * Which timer this stops. Optional, and worth sending: a stop that timed
			 * out on a flaky link and is retried after the person has started a new
			 * timer would otherwise end the new one. Named, it answers with the entry
			 * the first attempt filed instead.
			 */
			String timerId,
			Instant endedAt,
			String projectId,
			String issueId,
			@Size(max = MAX_DESCRIPTION) String description,
			@Size(max = MAX_ACTIVITY) String activityType,
			@Size(max = MAX_TAGS) List<@Size(max = MAX_TAG) String> tags,
			Boolean billable) {

		TimerService.StopRequest toRequest() {
			return new TimerService.StopRequest(timerId, endedAt, projectId, issueId, description,
					activityType, tags, billable);
		}
	}

	// --- routes ----------------------------------------------------------------

	/** The running timer, or 204 when none is running. */
	@GetMapping
	public ResponseEntity<TimerResponse> current() {
		return timers.current(currentUser.require())
				.map(timer -> ResponseEntity.ok(TimerResponse.from(timer)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** Starts one; 409 {@code error.time.timerAlreadyRunning} if one is already going. */
	@PostMapping("/start")
	@ResponseStatus(HttpStatus.CREATED)
	public TimerResponse start(@RequestBody(required = false) @Valid StartRequest request) {
		StartRequest body = request != null ? request
				: new StartRequest(null, null, null, null, null, null, null, null, null);
		return TimerResponse.from(timers.start(body.toDraft(), currentUser.require()));
	}

	/**
	 * Ends the running pomodoro phase and answers with the one that follows.
	 *
	 * <p>A work phase becomes an entry on the way; a break becomes nothing, which
	 * is the point — booked time is worked time. 400
	 * {@code error.time.notPomodoro} for a timer that has no phases to turn.
	 */
	@PostMapping("/phase")
	public TimerResponse phase(@RequestBody @Valid PhaseRequest request) {
		return TimerResponse.from(
				timers.advancePhase(request.timerId(), currentUser.require()));
	}

	/**
	 * Stops it and returns the entry it became.
	 *
	 * <p>Answers the same way twice. A repeated stop — a retry, two devices —
	 * returns the entry the first one filed rather than 404 or a second entry, so
	 * a client that never learns whether its request arrived can simply send it
	 * again.
	 */
	@PostMapping("/stop")
	public TimeEntryController.TimeEntryResponse stop(
			@RequestBody(required = false) @Valid StopRequest request) {
		User user = currentUser.require();
		StopRequest body = request != null ? request
				: new StopRequest(null, null, null, null, null, null, null, null);
		TimerService.Stopped stopped = timers.stop(body.toRequest(), user);
		return new TimeEntryController.TimeEntryResponse(
				TimeTrackingController.WorkItemResponse.from(stopped.entry()),
				timeTracking.overlapsOf(stopped.entry(), user));
	}

	/** Throws the timer away; nothing is recorded. */
	@PostMapping("/discard")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void discard() {
		timers.discard(currentUser.require());
	}

	/** Renames or re-files a timer that is still running. */
	@PatchMapping
	public TimerResponse patch(@RequestBody @Valid TimerRequest request) {
		return TimerResponse.from(timers.patch(request.toDraft(), currentUser.require()));
	}
}
