package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four time-tracking MCP tools after HIN-82 moved their bodies into
 * {@link TimeTrackingService}.
 *
 * <p>Two properties survive that move and are worth pinning. First, every tool
 * now inherits the service's project-membership check instead of repeating an
 * ACL of its own — so what these tests assert is the <em>delegation</em>: the
 * caller and the issue reference reach the service unchanged. Second, the two
 * places where MCP is deliberately narrower than the app: a timesheet is always
 * the token holder's own, and a deletion is owner-only — a token never inherits
 * the lead's power to remove someone else's entry.
 */
class TimeTrackingToolsTest {

	private TimeTrackingService timeTracking;
	private ScopeGuard scopeGuard;
	private TimeTrackingTools tools;
	private final User me = User.builder().id("u1").displayName("Agent").build();

	@BeforeEach
	void setUp() {
		timeTracking = mock(TimeTrackingService.class);
		scopeGuard = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(me);
		tools = new TimeTrackingTools(timeTracking, currentUser, scopeGuard,
				mock(AuditService.class, RETURNS_DEEP_STUBS));
	}

	private WorkItem item() {
		return WorkItem.builder().id("w1").issueId("i1").projectId("p1").userId("u1")
				.date(LocalDate.of(2026, 9, 7)).durationMinutes(30).activityType("Development")
				.description("work").createdAt(Instant.parse("2026-09-07T10:00:00Z")).build();
	}

	@Test
	void loggingWorkGoesThroughTheServiceAsTheCallerAndIsMarkedAsAnMcpEntry() {
		when(timeTracking.add(anyString(), any(), any(), any())).thenReturn(item());

		tools.log_work("HIN-42", 30, LocalDate.of(2026, 9, 7), "Testing", "note");

		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
		ArgumentCaptor<TimeTrackingService.NewWorkItem> draft =
				ArgumentCaptor.forClass(TimeTrackingService.NewWorkItem.class);
		verify(timeTracking).add(eq("HIN-42"), draft.capture(), eq(WorkItem.Source.MCP), eq(me));
		assertThat(draft.getValue().durationMinutes()).isEqualTo(30);
		assertThat(draft.getValue().date()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(draft.getValue().activityType()).isEqualTo("Testing");
		assertThat(draft.getValue().description()).isEqualTo("note");
	}

	/** The issue reference is passed on as given: the service resolves keys and ids alike. */
	@Test
	void listingGoesThroughTheAclCheckedListRatherThanTheRepository() {
		when(timeTracking.list(anyString(), any())).thenReturn(List.of(item()));

		List<TimeTrackingTools.WorkItemView> views = tools.listWorkItems("HIN-42");

		verify(scopeGuard).require(Scopes.WORKLOG_READ);
		verify(timeTracking).list("HIN-42", me);
		assertThat(views).singleElement().satisfies(view -> {
			assertThat(view.id()).isEqualTo("w1");
			assertThat(view.durationMinutes()).isEqualTo(30);
			assertThat(view.source()).isEqualTo("APP");
		});
	}

	/** A token can never inspect anybody else's hours — the caller's own id is not negotiable. */
	@Test
	void theTimesheetIsAlwaysTheCallersOwn() {
		when(timeTracking.timesheet(any(), any(), anyString(), any(), any()))
				.thenReturn(List.of());

		tools.myTimesheet(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), "p1");

		verify(timeTracking).timesheet(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
				me.getId(), "p1", me);
	}

	/** MCP deletes only what the caller owns: never the lead/admin elevation the REST route has. */
	@Test
	void deletionIsOwnerScopedAndNeverTheElevatedOne() {
		tools.delete_work_item("w1");

		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
		verify(timeTracking).deleteOwn("w1", me);
		verify(timeTracking, never()).delete(anyString(), any());
	}

	/** An entry written before 2.0 has no source and no tags; the view still reads. */
	@Test
	void theViewCarriesTheNewFieldsAndToleratesAPreTwoPointZeroEntry() {
		WorkItem modern = WorkItem.builder().id("w2").issueId("i1").projectId("p1").userId("u1")
				.date(LocalDate.of(2026, 9, 7)).durationMinutes(90)
				.startedAt(Instant.parse("2026-09-07T08:00:00Z"))
				.endedAt(Instant.parse("2026-09-07T09:30:00Z"))
				.billable(true).tags(new ArrayList<>(List.of("deep-work")))
				.source(WorkItem.Source.SMART_COMMIT)
				.updatedAt(Instant.parse("2026-09-07T11:00:00Z")).updatedBy("u2")
				.sharedFromId("w1").build();
		WorkItem legacy = WorkItem.builder().id("w3").source(null).tags(null).build();
		when(timeTracking.list(anyString(), any())).thenReturn(List.of(modern, legacy));

		List<TimeTrackingTools.WorkItemView> views = tools.listWorkItems("HIN-42");

		assertThat(views.getFirst().startedAt()).isEqualTo(Instant.parse("2026-09-07T08:00:00Z"));
		assertThat(views.getFirst().billable()).isTrue();
		assertThat(views.getFirst().tags()).containsExactly("deep-work");
		assertThat(views.getFirst().source()).isEqualTo("SMART_COMMIT");
		assertThat(views.getFirst().sharedFromId()).isEqualTo("w1");
		assertThat(views.getLast().source()).isEqualTo("APP");
		assertThat(views.getLast().tags()).isEmpty();
	}
}
