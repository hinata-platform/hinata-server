package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.RunningTimer;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.timetracking.TimerService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The timer tools of HIN-97. What they add to {@link TimerService} is what the HTTP side gets
 * from elsewhere — the scope gate, the module flag, the Bean Validation limits — and a refusal an
 * agent can read; the rules themselves (one timer per person, required fields at the stop) stay
 * the service's, so these tests pin the delegation and the guards in front of it.
 */
class TimerToolsTest {

	private TimerService timers;
	private TimeTrackingSettings settings;
	private ScopeGuard scopeGuard;
	private TimerTools tools;
	private final User me = User.builder().id("u1").displayName("Agent").build();

	@BeforeEach
	void setUp() {
		timers = mock(TimerService.class);
		settings = mock(TimeTrackingSettings.class);
		when(settings.advancedEnabled()).thenReturn(true);
		scopeGuard = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(me);
		tools = new TimerTools(timers, settings, currentUser, scopeGuard,
				new McpErrors(UserWordsFixture.real()), mock(AuditService.class, RETURNS_DEEP_STUBS));
	}

	private static RunningTimer running() {
		return RunningTimer.builder().id("t1").userId("u1").startedAt(Instant.parse("2026-10-11T08:00:00Z"))
				.projectId("p1").description("Review").mode(RunningTimer.Mode.POMODORO)
				.phase(RunningTimer.Phase.WORK).tags(List.of("deep-work")).build();
	}

	@Test
	void startingGoesThroughTheServiceWithTheModeAsked() {
		when(timers.start(any(), any())).thenReturn(running());

		TimerTools.TimerView view = tools.start_timer("Review", "p1", null, null, List.of("deep-work"), null,
				"pomodoro");

		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
		ArgumentCaptor<TimerService.StartDraft> draft = ArgumentCaptor.forClass(TimerService.StartDraft.class);
		verify(timers).start(draft.capture(), eq(me));
		assertThat(draft.getValue().mode()).isEqualTo(RunningTimer.Mode.POMODORO);
		assertThat(draft.getValue().content().projectId()).isEqualTo("p1");
		assertThat(draft.getValue().content().tags()).containsExactly("deep-work");
		assertThat(view.mode()).isEqualTo("POMODORO");
		assertThat(view.phase()).isEqualTo("WORK");
	}

	@Test
	void aSecondStartIsRefusedInWords() {
		when(timers.start(any(), any())).thenThrow(ApiException.conflict("error.time.timerAlreadyRunning"));

		assertThatThrownBy(() -> tools.start_timer(null, null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class)
				.hasMessage("A timer is already running");
	}

	@Test
	void anUnknownModeAndOversizedInputNeverReachTheService() {
		assertThatThrownBy(() -> tools.start_timer(null, null, null, null, null, null, "lap"))
				.hasMessage("That is not a value mode accepts");
		assertThatThrownBy(() -> tools.start_timer("x".repeat(2001), null, null, null, null, null, null))
				.hasMessage("description can be at most 2000 characters long");
		assertThatThrownBy(() -> tools.start_timer(null, null, null, null, Collections.nCopies(21, "t"), null,
				null)).hasMessage("tags takes at most 20 values");
		assertThatThrownBy(() -> tools.stop_timer("x".repeat(2001), null, null))
				.hasMessage("description can be at most 2000 characters long");
		verify(timers, never()).start(any(), any());
		verify(timers, never()).stop(any(), any());
	}

	@Test
	void everyTimerToolSaysTheModuleIsOffWhenItIs() {
		when(settings.advancedEnabled()).thenReturn(false);

		assertThatThrownBy(tools::get_timer).hasMessage("This feature is not enabled on this server");
		assertThatThrownBy(() -> tools.start_timer(null, null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> tools.stop_timer(null, null, null)).isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(tools::discard_timer).isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(tools::timerMarkdown).isInstanceOf(McpErrors.Refusal.class);
		verify(timers, never()).current(any());
	}

	/** The stop carries no timer id: a retry after a new start must not end the new timer. */
	@Test
	void stoppingFilesTheCallersTimerAndReturnsTheEntry() {
		WorkItem entry = WorkItem.builder().id("t1").userId("u1").date(LocalDate.of(2026, 10, 11))
				.durationMinutes(25).source(WorkItem.Source.TIMER).build();
		when(timers.stop(any(), any())).thenReturn(new TimerService.Stopped(entry, false));

		TimeTrackingTools.WorkItemView view = tools.stop_timer("Reviewed the PR", null, "HIN-97");

		ArgumentCaptor<TimerService.StopRequest> request = ArgumentCaptor.forClass(TimerService.StopRequest.class);
		verify(timers).stop(request.capture(), eq(me));
		assertThat(request.getValue().timerId()).isNull();
		assertThat(request.getValue().description()).isEqualTo("Reviewed the PR");
		assertThat(request.getValue().issueId()).isEqualTo("HIN-97");
		assertThat(view.durationMinutes()).isEqualTo(25);
		assertThat(view.source()).isEqualTo("TIMER");
	}

	@Test
	void stoppingWithoutATimerSaysSo() {
		when(timers.stop(any(), any())).thenThrow(ApiException.notFound("timer"));

		assertThatThrownBy(() -> tools.stop_timer(null, null, null)).hasMessage("Timer not found");
	}

	@Test
	void readingTheTimerIsReadOnlyAndAnswersWhenNoneRuns() {
		when(timers.current(me)).thenReturn(Optional.empty(), Optional.of(running()));

		assertThat(tools.get_timer()).isEqualTo(new TimerTools.TimerStatus(false, null));
		TimerTools.TimerStatus status = tools.get_timer();
		assertThat(status.running()).isTrue();
		assertThat(status.timer().description()).isEqualTo("Review");
		verify(scopeGuard, org.mockito.Mockito.times(2)).require(Scopes.WORKLOG_READ);
	}

	@Test
	void discardingThrowsTheCallersTimerAway() {
		assertThat(tools.discard_timer()).isEqualTo("discarded");

		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
		verify(timers).discard(me);
	}

	@Test
	void theTimerResourceReadsTheSameTimer() {
		when(timers.current(me)).thenReturn(Optional.empty(), Optional.of(running()));

		assertThat(tools.timerMarkdown()).contains("No timer is running.");
		assertThat(tools.timerMarkdown()).contains("- Mode: POMODORO").contains("- Tags: deep-work")
				.contains("Review");
	}
}
