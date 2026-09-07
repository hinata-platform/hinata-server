package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One gate for every change to an entry.
 *
 * <p>The value of this is not what the gate says today — that is the rule the
 * service already enforced — but that all three writes go through it, from
 * every caller there is: the app, an MCP tool, a smart commit. Stage 6 hangs the
 * lock date on it and stage 7 the approvals, and a write that had quietly gone
 * around it would be a way to change a frozen or approved timesheet.
 */
class TimeTrackingWriteHookTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC);
	private static final LocalDate TODAY = LocalDate.parse("2026-09-07");

	private final WorkItemRepository workItems = mock(WorkItemRepository.class);
	private final IssueService issues = mock(IssueService.class);
	private final ProjectService projects = mock(ProjectService.class);
	private final UserRepository users = mock(UserRepository.class);
	private final MongoTemplate mongo = mock(MongoTemplate.class);

	private TimeTrackingService service;

	private final User owner = user("u-owner");
	private final User peer = user("u-peer");
	private final User lead = user("u-lead");

	private static User user(String id) {
		return User.builder().id(id).username(id).email(id + "@example.test").build();
	}

	@BeforeEach
	void setUp() {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		service = spy(new TimeTrackingService(workItems, issues, projects,
				mock(ProjectReach.class), users, mongo, mock(AuditService.class), settings, CLOCK));
		when(workItems.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private WorkItem entry() {
		return WorkItem.builder().id("w-1").issueId("i-1").projectId("p-1").userId(owner.getId())
				.date(TODAY).durationMinutes(60).activityType("Development")
				.tags(List.of()).source(WorkItem.Source.APP).build();
	}

	private void issueExists() {
		Issue issue = Issue.builder().id("i-1").projectId("p-1").readableId("HIN-1").build();
		when(issues.getForUser(anyString(), any())).thenReturn(issue);
	}

	private void projectLedBy(User leadUser) {
		Project project = Project.builder().id("p-1").key("HIN").name("Hinata").build();
		when(projects.findOptional("p-1")).thenReturn(Optional.of(project));
		when(projects.isLeadOrAdmin(eq(project), any()))
				.thenAnswer(invocation -> leadUser.getId()
						.equals(((User) invocation.getArgument(1)).getId()));
	}

	// --- every write goes through the gate ---------------------------------

	@Test
	void loggingTimeGoesThroughTheGate() {
		issueExists();

		service.add("i-1", new TimeTrackingService.NewWorkItem(30, TODAY, null, null, null, null,
				null, null), WorkItem.Source.SMART_COMMIT, owner);

		verify(service).assertWritable(isNull(), any(WorkItem.class), eq(owner));
	}

	@Test
	void editingAnEntryGoesThroughTheGate() {
		when(workItems.findById("w-1")).thenReturn(Optional.of(entry()));

		service.update("w-1", new TimeTrackingService.WorkItemPatch(45, null, null, null,
				false, null, false, null, null, null), owner);

		// The gate sees the entry as it stands and the entry as it will stand.
		// Both, not either: from stage 6 a lock date or an approval covers the
		// day an entry is moving off as much as the day it is moving to, and only
		// a call carrying both can tell. Asserted as "the gate was told about the
		// old state and about the new one" rather than as a call count, which
		// would pass just as happily with the body replaced by return.
		ArgumentCaptor<WorkItem> before = ArgumentCaptor.forClass(WorkItem.class);
		ArgumentCaptor<WorkItem> after = ArgumentCaptor.forClass(WorkItem.class);
		verify(service, atLeastOnce())
				.assertWritable(before.capture(), after.capture(), eq(owner));
		assertThat(before.getAllValues()).allSatisfy(entry ->
				assertThat(entry.getDurationMinutes()).isEqualTo(60));
		assertThat(after.getAllValues()).anySatisfy(entry ->
				assertThat(entry.getDurationMinutes()).isEqualTo(45));
	}

	@Test
	void deletingAnEntryGoesThroughTheGate() {
		when(workItems.findById("w-1")).thenReturn(Optional.of(entry()));

		service.delete("w-1", owner);

		verify(service).assertWritable(any(WorkItem.class), isNull(), eq(owner));
	}

	@Test
	void theMcpOwnEntryDeleteGoesThroughTheGateToo() {
		when(workItems.findById("w-1")).thenReturn(Optional.of(entry()));

		service.deleteOwn("w-1", owner);

		verify(service).assertWritable(any(WorkItem.class), isNull(), eq(owner));
	}

	// --- what the gate says today ------------------------------------------

	@Test
	void theOwnerMayWriteTheirOwnEntry() {
		WorkItem item = entry();

		service.assertWritable(item, item, owner);
		service.assertWritable(item, null, owner);
		service.assertWritable(null, item, owner);
	}

	@Test
	void aLeadOfTheProjectMayWriteAMembersEntry() {
		projectLedBy(lead);

		service.assertWritable(entry(), entry(), lead);
	}

	@Test
	void aPeerMayNotAndIsToldWhichThingTheyCannotDo() {
		projectLedBy(lead);
		WorkItem item = entry();

		assertThatThrownBy(() -> service.assertWritable(item, item, peer))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(ex.getMessageKey()).isEqualTo("error.time.editOwnOnly");
				});
		assertThatThrownBy(() -> service.assertWritable(item, null, peer))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.time.deleteOwnOnly"));
	}

	@Test
	void anEntryIsNeverCreatedForSomebodyElse() {
		WorkItem forSomeoneElse = entry().toBuilder().userId(peer.getId()).build();

		// Nothing builds one today — the smart-commit path logs against the commit
		// author, as that author. This is where that stops being a property of the
		// call sites and becomes a rule.
		assertThatThrownBy(() -> service.assertWritable(null, forSomeoneElse, owner))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.time.editOwnOnly"));
	}

	@Test
	void aForeignEditIsRefusedBeforeTheChangeItselfIsInspected() {
		projectLedBy(lead);
		when(workItems.findById("w-1")).thenReturn(Optional.of(entry()));

		// A duration of 0 is invalid input; the answer is still "not your entry",
		// because the question of whose entry it is comes first.
		assertThatThrownBy(() -> service.update("w-1",
				new TimeTrackingService.WorkItemPatch(0, null, null, null, false, null, false,
						null, null, null), peer))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.time.editOwnOnly"));
	}
}
