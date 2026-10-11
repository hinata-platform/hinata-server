package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.mcp.McpErrors;
import com.ahmadre.hinata.mcp.ScopeGuard;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two absence tools of HIN-97: the caller's own balance and a request, through the services
 * the app uses — and nothing about sick leave, which an agent neither reads nor reports (R11).
 */
class TimeOffToolsTest {

	private TimeOffBalanceService balances;
	private TimeOffTypeService types;
	private TimeOffRequestService requests;
	private TimeOffSettings settings;
	private ScopeGuard scopeGuard;
	private TimeOffTools tools;
	private final User me = User.builder().id("u1").displayName("Agent").build();

	@BeforeEach
	void setUp() {
		balances = mock(TimeOffBalanceService.class);
		types = mock(TimeOffTypeService.class);
		requests = mock(TimeOffRequestService.class);
		settings = mock(TimeOffSettings.class);
		when(settings.enabled()).thenReturn(true);
		scopeGuard = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(me);
		tools = new TimeOffTools(balances, types, requests, settings, currentUser, scopeGuard,
				new McpErrors(UserWordsFixture.real()), UserWordsFixture.real(),
				Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC));
	}

	private static TimeOffBalanceService.Balance balance(String typeId, int remaining) {
		return new TimeOffBalanceService.Balance(typeId, 2026, 25_000, 25_000, 0, 0, 10_500, 2_000, 0, 0,
				remaining, null, true, false, null, false, 20_000, 3_000, LocalDate.of(2027, 3, 31));
	}

	@Test
	void theBalanceIsTheCallersOwnAndLeavesSickLeaveOut() {
		when(types.list(me, true)).thenReturn(List.of(
				TimeOffType.builder().id("vac").systemKey("vacation").kind(TimeOffType.Kind.VACATION).build(),
				TimeOffType.builder().id("sick").systemKey("sick").kind(TimeOffType.Kind.SICK).build(),
				TimeOffType.builder().id("edu").name("Bildungsurlaub").kind(TimeOffType.Kind.TRAINING).build()));
		when(balances.balances(me, "u1", 2026)).thenReturn(List.of(balance("vac", 12_500),
				balance("sick", 0), balance("edu", 5_000)));

		List<TimeOffTools.BalanceView> rows = tools.my_time_off_balance(null);

		verify(scopeGuard).require(Scopes.WORKLOG_READ);
		verify(balances).balances(me, "u1", 2026);
		assertThat(rows).extracting(TimeOffTools.BalanceView::typeId).containsExactly("vac", "edu");
		assertThat(rows.getFirst().type()).isEqualTo("Vacation");
		assertThat(rows.getFirst().remainingDays()).isEqualTo(12.5);
		assertThat(rows.getFirst().takenDays()).isEqualTo(10.5);
		assertThat(rows.getFirst().expiringDays()).isEqualTo(3.0);
		assertThat(rows.getLast().type()).isEqualTo("Bildungsurlaub");
	}

	@Test
	void bothToolsAndTheResourceSayTheModuleIsOffWhenItIs() {
		when(settings.enabled()).thenReturn(false);

		assertThatThrownBy(() -> tools.my_time_off_balance(2026))
				.isInstanceOf(McpErrors.Refusal.class)
				.hasMessage("This feature is not enabled on this server");
		assertThatThrownBy(() -> tools.request_time_off("vac", LocalDate.of(2026, 12, 21), null, null, null,
				null, null)).isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(tools::balanceResource).isInstanceOf(McpErrors.Refusal.class);
		verify(balances, never()).balances(any(), anyString(), anyInt());
		verify(requests, never()).submit(any(), any());
	}

	@Test
	void aRequestGoesThroughTheSameSubmissionAsTheApp() {
		when(requests.submit(any(), any())).thenReturn(TimeOffRequest.builder().id("r1").typeId("vac")
				.from(LocalDate.of(2026, 12, 21)).to(LocalDate.of(2026, 12, 23)).milliDays(2_500).workingDays(3)
				.status(TimeOffRequest.Status.SUBMITTED).build());

		TimeOffTools.RequestView view = tools.request_time_off("vac", LocalDate.of(2026, 12, 21),
				LocalDate.of(2026, 12, 23), null, 500, "Weihnachten", "u2");

		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
		ArgumentCaptor<TimeOffRequestService.Draft> draft = ArgumentCaptor.forClass(TimeOffRequestService.Draft.class);
		verify(requests).submit(eq(me), draft.capture());
		assertThat(draft.getValue()).isEqualTo(new TimeOffRequestService.Draft("vac", LocalDate.of(2026, 12, 21),
				LocalDate.of(2026, 12, 23), null, 500, "Weihnachten", "u2"));
		assertThat(view.days()).isEqualTo(2.5);
		assertThat(view.status()).isEqualTo("SUBMITTED");
	}

	/** Sick leave is reported in the app, never through an agent; the service says so. */
	@Test
	void sickLeaveIsRefusedWithTheServicesReason() {
		when(requests.submit(any(), any())).thenThrow(ApiException.badRequest("error.timeOff.sickNotRequested"));

		assertThatThrownBy(() -> tools.request_time_off("sick", LocalDate.of(2026, 10, 12), null, null, null,
				null, null)).hasMessageStartingWith("Sick leave is reported, not applied for.");
	}

	@Test
	void theLimitsTheRestRecordStatesAreCheckedByHand() {
		assertThatThrownBy(() -> tools.request_time_off("vac", LocalDate.of(2026, 12, 21), null, 0, null, null,
				null)).hasMessage("firstDayMilliDays must be between 1 and 1000");
		assertThatThrownBy(() -> tools.request_time_off("vac", LocalDate.of(2026, 12, 21), null, null, 1001,
				null, null)).hasMessage("lastDayMilliDays must be between 1 and 1000");
		assertThatThrownBy(() -> tools.request_time_off("vac", LocalDate.of(2026, 12, 21), null, null, null,
				"x".repeat(TimeOffRequest.NOTE_MAX + 1), null)).hasMessageStartingWith("note can be at most");
		verify(requests, never()).submit(any(), any());
	}

	@Test
	void theBalanceResourceSpellsTheSameFigures() {
		when(types.list(me, true)).thenReturn(List.of(
				TimeOffType.builder().id("vac").systemKey("vacation").kind(TimeOffType.Kind.VACATION).build()));
		when(balances.balances(me, "u1", 2026)).thenReturn(List.of(balance("vac", 12_500)));

		assertThat(tools.balanceResource()).startsWith("# Time off 2026")
				.contains("- Vacation: 12.5 of 25.0 left, 2.0 planned, 3.0 lapse on 2027-03-31");
	}
}
