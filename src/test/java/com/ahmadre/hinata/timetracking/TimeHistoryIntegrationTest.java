package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.availability.Holiday;
import com.ahmadre.hinata.availability.HolidayCalendar;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Burn-down history, the forecast and sprint budgets (HIN-156), against a real database.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeHistoryIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeHistoryService history;
	@Autowired
	private TimeUsageController usageRoutes;
	@Autowired
	private SprintTimeController sprintRoutes;
	@Autowired
	private TimeBudgetGuard guard;
	@Autowired
	private TimeUsageCache cache;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TestClock clock;
	@MockitoBean
	private CurrentUser currentUser;
	@Autowired
	private com.ahmadre.hinata.report.ReportController reportRoutes;
	@Autowired
	private TimeTrackingErasure erasure;

	private User lead;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class, ProjectTimeSettings.class,
				SprintTimeSettings.class, Sprint.class, AgileBoard.class, Holiday.class, HolidayCalendar.class)) {
			mongo.remove(new Query(), type);
		}
		cache.forgetAll();
		settings.save(new ServerSettings());
		clock.set(TestClock.START);
		lead = person("lead");
		member = person("member");
		project = mongo.insert(Project.builder().key("APO").name("Apollo").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId()))).build());
		when(currentUser.require()).thenReturn(member);
	}

	@Test
	void theHistoryCountsDaysInTheSpanAndEverythingBeforeIt() {
		record(LocalDate.of(2026, 8, 1), 100, null);
		record(LocalDate.of(2026, 9, 1), 30, null);
		record(LocalDate.of(2026, 9, 1), 20, null);
		record(LocalDate.of(2026, 9, 3), 60, null);
		record(LocalDate.of(2026, 9, 20), 999, null);

		TimeHistoryService.History span = history.history(project.getId(), LocalDate.of(2026, 9, 1), TODAY);

		assertThat(span.before()).isEqualTo(100);
		assertThat(span.days()).extracting(TimeHistoryService.Day::date, TimeHistoryService.Day::minutes)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 1), 50L),
						org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 3), 60L));
	}

	@Test
	void aHistoryLongerThanAYearIsRefused() {
		assertThatThrownBy(() -> usageRoutes.history(project.getId(), TODAY.minusDays(400), TODAY))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void theForecastNeedsThreeDaysOfDataAndSkipsHolidays() {
		budget(6000);
		record(TODAY, 120, null);
		record(TODAY.minusDays(1), 120, null);
		assertThat(history.forecast(project.getId()).confidence()).isEqualTo(TimeHistoryService.Confidence.LOW);
		assertThat(history.forecast(project.getId()).exhaustedOn()).isNull();

		record(TODAY.minusDays(3), 120, null);
		cache.forgetAll();
		TimeHistoryService.Forecast plain = history.forecast(project.getId());
		assertThat(plain.confidence()).isEqualTo(TimeHistoryService.Confidence.OK);
		assertThat(plain.exhaustedOn()).isAfter(TODAY);
		assertThat(plain.exhaustedOn().getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

		HolidayCalendar calendar = mongo.insert(HolidayCalendar.builder().name("DE").defaultCalendar(true).build());
		mongo.insert(Holiday.builder().calendarId(calendar.getId()).date(plain.exhaustedOn()).name("Fest").build());
		assertThat(history.forecast(project.getId()).exhaustedOn()).isAfter(plain.exhaustedOn());
	}

	@Test
	void aSprintCountsItsIssuesWithinItsDatesAndOnlyALeadSetsItsBudget() {
		AgileBoard board = mongo.insert(AgileBoard.builder().name("Board")
				.projectIds(new ArrayList<>(List.of(project.getId()))).build());
		Sprint sprint = mongo.insert(Sprint.builder().boardId(board.getId()).name("Sprint 1")
				.startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 14)).build());
		Issue inSprint = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-1").numberInProject(1)
				.title("a").state("Open").sprintId(sprint.getId()).assigneeIds(new ArrayList<>()).build());
		Issue outside = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-2").numberInProject(2)
				.title("b").state("Open").assigneeIds(new ArrayList<>()).build());
		record(LocalDate.of(2026, 9, 2), 60, inSprint.getId());
		record(LocalDate.of(2026, 8, 20), 500, inSprint.getId());
		record(LocalDate.of(2026, 9, 2), 500, outside.getId());

		assertThatThrownBy(() -> sprintRoutes.put(sprint.getId(), new SprintTimeController.SprintBudgetRequest(100)))
				.isInstanceOf(ApiException.class);
		when(currentUser.require()).thenReturn(lead);
		TimeHistoryService.SprintUsage usage = sprintRoutes.put(sprint.getId(),
				new SprintTimeController.SprintBudgetRequest(70));

		assertThat(usage.usedMinutes()).isEqualTo(60);
		assertThat(usage.budgetMinutes()).isEqualTo(70);
		WorkItem saved = record(LocalDate.of(2026, 9, 3), 1, inSprint.getId());
		assertThat(guard.warningsAfter(saved, lead)).extracting(TimeBudgetGuard.BudgetWarning::kind)
				.contains(TimeBudgetGuard.Kind.SPRINT);
	}

	@Test
	void theBudgetReportPagesTheFullestFirstAndListsTheSprintsTheReaderSees() {
		budget(600);
		record(TODAY, 540, null);
		Project second = mongo.insert(Project.builder().key("GEM").name("Gemini").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId()))).build());
		mongo.insert(ProjectTimeSettings.builder().projectId(second.getId()).budgetMinutes(1000).build());
		Project hidden = mongo.insert(Project.builder().key("HID").name("Hidden").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId()))).build());
		mongo.insert(ProjectTimeSettings.builder().projectId(hidden.getId()).budgetMinutes(10).build());
		AgileBoard board = mongo.insert(AgileBoard.builder().name("Board")
				.projectIds(new ArrayList<>(List.of(project.getId()))).build());
		Sprint sprint = mongo.insert(Sprint.builder().boardId(board.getId()).name("Sprint 1")
				.startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 14)).build());
		mongo.insert(SprintTimeSettings.builder().sprintId(sprint.getId()).budgetMinutes(120).build());
		AgileBoard hiddenBoard = mongo.insert(AgileBoard.builder().name("Other")
				.projectIds(new ArrayList<>(List.of(hidden.getId()))).build());
		Sprint hiddenSprint = mongo.insert(Sprint.builder().boardId(hiddenBoard.getId()).name("Secret").build());
		mongo.insert(SprintTimeSettings.builder().sprintId(hiddenSprint.getId()).budgetMinutes(120).build());
		cache.forgetAll();

		TimeUsageController.BudgetReport first = usageRoutes.budgetReport(0, 1, null);
		TimeUsageController.BudgetReport next = usageRoutes.budgetReport(1, 1, null);

		assertThat(first.total()).isEqualTo(2);
		assertThat(first.items()).extracting(TimeUsageService.ProjectUsage::projectId).containsExactly(project.getId());
		assertThat(next.items()).extracting(TimeUsageService.ProjectUsage::projectId).containsExactly(second.getId());
		assertThat(first.sprints()).extracting(TimeUsageController.SprintRow::name).containsExactly("Sprint 1");
		assertThat(next.sprints()).isEmpty();
	}

	@Test
	void aSprintCountsOnlyTheProjectsItsReaderSees() {
		Project hidden = mongo.insert(Project.builder().key("SEC").name("Secret").leadId("someone")
				.leadIds(new ArrayList<>(List.of("someone"))).memberIds(new ArrayList<>(List.of("someone"))).build());
		AgileBoard board = mongo.insert(AgileBoard.builder().name("Both")
				.projectIds(new ArrayList<>(List.of(project.getId(), hidden.getId()))).build());
		Sprint sprint = mongo.insert(Sprint.builder().boardId(board.getId()).name("Sprint 1")
				.startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 14)).build());
		Issue mine = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-1").numberInProject(1)
				.title("a").state("Open").sprintId(sprint.getId()).assigneeIds(new ArrayList<>()).build());
		Issue theirs = mongo.insert(Issue.builder().projectId(hidden.getId()).readableId("SEC-1").numberInProject(1)
				.title("b").state("Open").sprintId(sprint.getId()).assigneeIds(new ArrayList<>()).build());
		mongo.insert(SprintTimeSettings.builder().sprintId(sprint.getId()).budgetMinutes(600).build());
		record(LocalDate.of(2026, 9, 2), 60, mine.getId());
		mongo.insert(WorkItem.builder().userId("someone").projectId(hidden.getId()).issueId(theirs.getId())
				.date(LocalDate.of(2026, 9, 2)).durationMinutes(500).build());

		assertThat(sprintRoutes.usage(sprint.getId()).usedMinutes()).isEqualTo(60);
		assertThat(usageRoutes.budgetReport(0, 20, null).sprints()).singleElement()
				.satisfies(row -> assertThat(row.usedMinutes()).isEqualTo(60));
		// A member of the board's project uses the board but does not run it.
		assertThat(sprintRoutes.usage(sprint.getId()).canEdit()).isFalse();
	}

	@Test
	void aDeletedAccountLeavesNoNameOnTheBudgetsItChanged() {
		budget(600);
		mongo.updateMulti(new Query(), new org.springframework.data.mongodb.core.query.Update()
				.set("updatedBy", lead.getId()), ProjectTimeSettings.class);
		mongo.insert(SprintTimeSettings.builder().sprintId("s1").budgetMinutes(60).updatedBy(lead.getId()).build());

		erasure.onUserDeleted(new com.ahmadre.hinata.user.UserService.UserDeletedEvent(lead.getId()));

		assertThat(mongo.findAll(ProjectTimeSettings.class)).allSatisfy(own -> assertThat(own.getUpdatedBy()).isNull());
		assertThat(mongo.findAll(SprintTimeSettings.class)).allSatisfy(own -> assertThat(own.getUpdatedBy()).isNull());
	}

	@Test
	void theOverviewAnswersEverythingInOneCall() {
		mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-9").numberInProject(9).title("x")
				.state("Open").assigneeIds(new ArrayList<>()).build());

		com.ahmadre.hinata.report.ReportController.Overview overview = reportRoutes.overview(project.getId(), 9999);

		// Capped at the longest trend there is, and the trend covers every day the answer names.
		assertThat(overview.days()).isEqualTo(180);
		assertThat(overview.trend()).hasSize(overview.days());
		assertThat(overview.byState()).containsEntry("Open", 1L);
		assertThat(overview.byAssignee()).containsEntry(com.ahmadre.hinata.report.ReportService.UNASSIGNED, 1L);
	}

	private User person(String name) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.active(true).locale("en").build());
	}

	private void budget(int minutes) {
		mongo.insert(ProjectTimeSettings.builder().projectId(project.getId()).budgetMinutes(minutes).build());
		cache.forgetAll();
	}

	private WorkItem record(LocalDate day, int minutes, String issueId) {
		cache.forgetAll();
		return mongo.insert(WorkItem.builder().userId(member.getId()).projectId(project.getId()).issueId(issueId)
				.date(day).durationMinutes(minutes).build());
	}
}
