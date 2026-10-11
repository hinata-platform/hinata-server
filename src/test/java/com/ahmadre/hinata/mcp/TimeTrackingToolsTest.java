package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.timetracking.TimeReportFilter;
import com.ahmadre.hinata.timetracking.TimeReportQuery;
import com.ahmadre.hinata.timetracking.TimeReportService;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
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
	private TimeReportService reports;
	private TimeTrackingSettings settings;
	private ScopeGuard scopeGuard;
	private TimeTrackingTools tools;
	private final User me = User.builder().id("u1").displayName("Agent").build();

	@BeforeEach
	void setUp() {
		timeTracking = mock(TimeTrackingService.class);
		reports = mock(TimeReportService.class);
		settings = mock(TimeTrackingSettings.class);
		when(settings.advancedEnabled()).thenReturn(true);
		scopeGuard = mock(ScopeGuard.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(me);
		tools = new TimeTrackingTools(timeTracking, reports, settings, currentUser, scopeGuard,
				new McpErrors(UserWordsFixture.real()), mock(AuditService.class, RETURNS_DEEP_STUBS));
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
		when(timeTracking.detailsVisibleTo(me)).thenReturn(entry -> true);

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
		when(timeTracking.detailsVisibleTo(me)).thenReturn(entry -> true);

		List<TimeTrackingTools.WorkItemView> views = tools.listWorkItems("HIN-42");

		assertThat(views.getFirst().startedAt()).isEqualTo(Instant.parse("2026-09-07T08:00:00Z"));
		assertThat(views.getFirst().billable()).isTrue();
		assertThat(views.getFirst().tags()).containsExactly("deep-work");
		assertThat(views.getFirst().source()).isEqualTo("SMART_COMMIT");
		assertThat(views.getFirst().sharedFromId()).isEqualTo("w1");
		assertThat(views.getLast().source()).isEqualTo("APP");
		assertThat(views.getLast().tags()).isEmpty();
	}

	/** A token reads a colleague's entry the way its holder may: the hours without the person. */
	@Test
	void anEntryTheHolderMayNotReadComesBackWithoutItsDetails() {
		WorkItem colleagues = WorkItem.builder().id("w4").issueId("i1").projectId("p1")
				.userId("u-colleague").date(LocalDate.of(2026, 9, 7)).durationMinutes(45)
				.description("Arzttermin nachgeholt").source(WorkItem.Source.APP).build();
		when(timeTracking.list(anyString(), any())).thenReturn(List.of(colleagues));
		when(timeTracking.detailsVisibleTo(me)).thenReturn(entry -> false);

		List<TimeTrackingTools.WorkItemView> views = tools.listWorkItems("HIN-42");

		assertThat(views).singleElement().satisfies(view -> {
			assertThat(view.hidden()).isTrue();
			assertThat(view.durationMinutes()).isEqualTo(45);
			assertThat(view.userId()).isNull();
			assertThat(view.description()).isNull();
		});
	}

	// --- HIN-97 ---------------------------------------------------------------------

	/** The four 1.x tools are what the published app and existing clients use with the module off. */
	@Test
	void theOldToolsKeepWorkingWithTheModuleOffAndTheNewOnesSaySo() {
		when(settings.advancedEnabled()).thenReturn(false);
		when(timeTracking.add(anyString(), any(), any(), any())).thenReturn(item());

		assertThat(tools.log_work("HIN-42", 30, null, null, null).id()).isEqualTo("w1");
		assertThatThrownBy(() -> tools.list_my_work_items(LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30), null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class)
				.hasMessage("This feature is not enabled on this server");
		assertThatThrownBy(() -> tools.update_work_item("w1", 10, null, null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> tools.time_summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				null, null, null, null)).isInstanceOf(McpErrors.Refusal.class);
		verify(timeTracking, never()).entries(any(), anyInt(), anyInt(), any());
		verify(timeTracking, never()).update(anyString(), any(), any());
	}

	@Test
	void listingMyEntriesIsTheCallersOwnAndPaged() {
		when(timeTracking.entries(any(), anyInt(), anyInt(), any()))
				.thenReturn(new PageImpl<>(List.of(item()), PageRequest.of(1, 10), 11));

		McpViews.PageResult<TimeTrackingTools.WorkItemView> page = tools.list_my_work_items(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "p1", "work", 1, 10);

		verify(scopeGuard).require(Scopes.WORKLOG_READ);
		ArgumentCaptor<TimeTrackingService.EntryFilter> filter =
				ArgumentCaptor.forClass(TimeTrackingService.EntryFilter.class);
		verify(timeTracking).entries(filter.capture(), eq(1), eq(10), eq(me));
		assertThat(filter.getValue()).isEqualTo(new TimeTrackingService.EntryFilter(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "p1", "work"));
		assertThat(page.items()).extracting(TimeTrackingTools.WorkItemView::id).containsExactly("w1");
		assertThat(page.totalElements()).isEqualTo(11);
	}

	/** MCP has no Bean Validation: the limits the REST record states are checked by hand. */
	@Test
	void textsLongerThanTheRestRoutesAcceptAreRefusedBeforeTheService() {
		assertThatThrownBy(() -> tools.list_my_work_items(LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30), null, "x".repeat(101), null, null))
				.hasMessage("q can be at most 100 characters long");
		assertThatThrownBy(() -> tools.update_work_item("w1", null, null, null, null, "x".repeat(2001),
				null, null, null)).hasMessage("description can be at most 2000 characters long");
		assertThatThrownBy(() -> tools.update_work_item("w1", 0, null, null, null, null, null, null, null))
				.hasMessage("durationMinutes must be between 1 and 1440");
		assertThatThrownBy(() -> tools.update_work_item("w1", null, null, null, null, null, null,
				java.util.Collections.nCopies(21, "t"), null)).hasMessage("tags takes at most 20 values");
		verify(timeTracking, never()).update(anyString(), any(), any());
	}

	/** Owner first, then the edit: the lead or admin its holder may be stays out of a token's reach. */
	@Test
	void anUpdateIsOwnerOnlyAndAColleaguesEntryReadsAsMissing() {
		when(timeTracking.requireOwn("w-colleague", me)).thenThrow(ApiException.notFound("workItem"));

		assertThatThrownBy(() -> tools.update_work_item("w-colleague", 10, null, null, null, null, null,
				null, null))
				.isInstanceOf(McpErrors.Refusal.class)
				.hasMessage("Work item not found");
		verify(timeTracking, never()).update(anyString(), any(), any());
	}

	@Test
	void anUpdateCarriesOnlyTheGivenFieldsThroughTheService() {
		WorkItem saved = item();
		when(timeTracking.update(anyString(), any(), any())).thenReturn(saved);
		Instant start = Instant.parse("2026-09-07T08:00:00Z");
		Instant end = Instant.parse("2026-09-07T09:00:00Z");

		tools.update_work_item("w1", null, null, start, end, "fixed", null, null, true);

		var order = inOrder(timeTracking);
		order.verify(timeTracking).requireOwn("w1", me);
		ArgumentCaptor<TimeTrackingService.WorkItemPatch> patch =
				ArgumentCaptor.forClass(TimeTrackingService.WorkItemPatch.class);
		order.verify(timeTracking).update(eq("w1"), patch.capture(), eq(me));
		assertThat(patch.getValue()).isEqualTo(new TimeTrackingService.WorkItemPatch(null, null, null,
				"fixed", true, start, true, end, null, true));
		verify(scopeGuard).require(Scopes.WORKLOG_WRITE);
	}

	/** An invoiced entry is refused by the service, and the agent is told why and what frees it. */
	@Test
	void aLockedEntrySaysWhichLockAndWhatWouldFreeIt() {
		when(timeTracking.update(anyString(), any(), any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN,
				"error.time.locked", Map.of("remedy", "creditNote", "invoiceId", "inv1", "reason", "invoice",
						"holder", "billing")));

		assertThatThrownBy(() -> tools.update_work_item("w1", 10, null, null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class)
				.hasMessageEndingWith("(reason: invoice; holder: billing; remedy: creditNote; invoiceId: inv1)")
				.hasMessageNotContaining("error.time.locked");
	}

	@Test
	void theSummaryIsTheCallersOwnHoursAndCapped() {
		TimeReportFilter filter = mock(TimeReportFilter.class);
		when(reports.filter(eq(me), any())).thenReturn(filter);
		List<TimeReportService.Group> groups = List.of(new TimeReportService.Group("p1", "Hinata", "HIN",
				90, 60, 2));
		when(reports.summary(eq(me), eq(filter), any(), anyInt(), anyInt())).thenReturn(
				new TimeReportService.Summary(new TimeReportService.Totals(90, 90, 60, 2),
						TimeReportService.GroupBy.TAG,
						new PageImpl<>(groups, PageRequest.of(0, 100), 140), true));

		TimeTrackingTools.TimeSummaryView view = tools.time_summary(LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31), "tag", "p1", "up", 15);

		ArgumentCaptor<TimeReportQuery> query = ArgumentCaptor.forClass(TimeReportQuery.class);
		verify(reports).filter(eq(me), query.capture());
		assertThat(query.getValue().userIds()).containsExactly("u1");
		assertThat(query.getValue().projectIds()).containsExactly("p1");
		assertThat(query.getValue().roundingIncrement()).isEqualTo(15);
		verify(reports).summary(me, filter, TimeReportService.GroupBy.TAG, 0, 100);
		assertThat(view.groups()).hasSize(1);
		assertThat(view.groupCount()).isEqualTo(140);
		assertThat(view.truncated()).isTrue();
		assertThat(view.totals().minutes()).isEqualTo(90);
	}

	/** A report by person or team is a report about colleagues, which a token never reads. */
	@Test
	void theSummaryIsNeverGroupedByPeople() {
		for (String people : List.of("USER", "team")) {
			assertThatThrownBy(() -> tools.time_summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
					people, null, null, null)).hasMessage("That is not a value groupBy accepts");
		}
		assertThatThrownBy(() -> tools.time_summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
				"PROJECT", null, "sideways", null)).hasMessage("That is not a value rounding accepts");
		verify(reports, never()).summary(any(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void theEntryResourceIsTheCallersOwnOnly() {
		when(timeTracking.requireOwn("w-colleague", me)).thenThrow(ApiException.notFound("workItem"));
		when(timeTracking.requireOwn("w1", me)).thenReturn(item());

		assertThat(tools.entryMarkdown("w1")).contains("# Work item w1").contains("- Minutes: 30")
				.contains("work");
		assertThatThrownBy(() -> tools.entryMarkdown("w-colleague")).hasMessage("Work item not found");
		verify(scopeGuard, org.mockito.Mockito.times(2)).require(Scopes.WORKLOG_READ);
	}
}
