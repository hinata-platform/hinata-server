package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.BoardController;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.dashboard.DashboardPrefsRepository;
import com.ahmadre.hinata.dashboard.DashboardService;
import com.ahmadre.hinata.gantt.GanttController;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.DueDateReminderJob;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.report.ReportController;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.weeklysummary.WeeklySummaryService;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The six issue surfaces that went around {@code IssueService.search} and served
 * frozen titles anyway.
 *
 * <p>Each of these had its own way of not using the seam — the board and the Gantt
 * chart through derived repository finders, the dashboard, the weekly summary, the
 * reports and the due-date reminder through raw
 * {@code mongo.find(query, Issue.class)}. They now share
 * {@link FrozenIssues}, and this class is the behavioural half of that: the wiring
 * test says the collaborator is reached, these say the frozen row is actually gone
 * from what each surface returns.
 *
 * <p>Against a real MongoDB for the query-based ones, for the reason
 * {@code FrozenIssueListMongoTest} gives at length: what is under test for those is
 * whether a {@code $nin} composed into a query document narrows it, which is a
 * property of the query and not of the code that assembles one. The two
 * finder-based ones (board, Gantt) filter a list in memory and are exercised
 * through mocked repositories, because there is no query there to be wrong about.
 *
 * <p><b>The due-date reminder is the one that matters most.</b> Every other row
 * here serves content to somebody who asked for it. That job mints notifications
 * and pushes a frozen issue's title out by e-mail and push, daily, with no human
 * in the loop — a freeze cannot recall what already left, so all it can do is stop
 * tomorrow's send, and this is the test that says it does.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenIssueSurfacesMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String PROJECT = "p-1";
	private static final String FROZEN_TITLE = "Frozen title nobody may read";

	private final User admin = user("u-admin", true);

	private MongoTemplate mongo;
	private ProjectService projects;
	private Project project;
	private FrozenIssues frozenIssues;
	private FrozenIssues nothingFrozen;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-surfaces");
		mongo.getDb().drop();

		project = Project.builder().id(PROJECT).key("HIN").name("Hinata").archived(false)
				.memberIds(new ArrayList<>(List.of("u-admin")))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build();
		projects = mock(ProjectService.class);
		when(projects.visibleTo(any())).thenReturn(List.of(project));
		when(projects.activeProjectIds()).thenReturn(Set.of(PROJECT));
		when(projects.findOptional(PROJECT)).thenReturn(Optional.of(project));
		when(projects.get(PROJECT)).thenReturn(project);

		// Two rows: one issue that is resolved (the highlight/report/board case) and one
		// that is still open (the upcoming/today/reminder case), because several of the
		// surfaces below only look at one of the two states.
		frozenIssues = new FrozenIssues(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen"),
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen-open")));
		nothingFrozen = new FrozenIssues(FreezeFixtures.nothingFrozen());
	}

	// --- 1. the board ------------------------------------------------------------

	/**
	 * A board card is the most-read rendering of an issue in the product and the one
	 * nobody has to open anything to see: it shows the readable id and the title
	 * side by side. {@code BoardController} loads its rows through
	 * {@code IssueRepository.findByProjectId}, so it inherited none of
	 * {@code IssueService.search}'s filters — the freeze was working perfectly on the
	 * detail view behind a card that was still showing the title.
	 */
	@Test
	void aFrozenIssueIsAbsentFromEveryBoardColumn() {
		BoardController.BoardView view = board(frozenIssues).view("b-1", null);

		assertThat(titlesOn(view)).doesNotContain(FROZEN_TITLE).contains("Ordinary one");
	}

	@Test
	void withNothingFrozenTheBoardIsExactlyWhatItAlwaysWas() {
		BoardController.BoardView view = board(nothingFrozen).view("b-1", null);

		assertThat(titlesOn(view)).contains(FROZEN_TITLE, "Ordinary one");
	}

	// --- 2. the Gantt timeline ---------------------------------------------------

	/**
	 * The bar carries the title and the readable id, and the connector graph beside
	 * it carries the id — enough to confirm the issue exists and where it sits.
	 * Filtering once, before both are built, is what keeps them consistent.
	 */
	@Test
	void aFrozenIssueHasNoBarOnTheTimeline() {
		IssueRepository repo = mock(IssueRepository.class);
		when(repo.findScheduled(PROJECT)).thenReturn(List.of(
				scheduled("i-frozen", FROZEN_TITLE), scheduled("i-open", "Ordinary one")));
		IssueLinkGraphService graph = mock(IssueLinkGraphService.class);
		when(graph.among(anyList())).thenReturn(List.of());

		GanttController.GanttView view =
				new GanttController(repo, projects, graph, frozenIssues, currentUser()).tasks(PROJECT);

		assertThat(view.tasks()).extracting(GanttController.GanttTask::title)
				.doesNotContain(FROZEN_TITLE)
				.contains("Ordinary one");
	}

	// --- 3. the due-date reminder — the egress path ------------------------------

	/**
	 * No new notification, no push and no e-mail for a frozen issue. This is the
	 * only path in the enumeration that pushes content <em>outward</em> on a timer,
	 * so it is the only one where a missing guard keeps re-publishing the material
	 * after the freeze rather than merely failing to withhold it.
	 */
	@Test
	void theDueDateReminderNeverNotifiesAboutAFrozenIssue() {
		mongo.save(due("i-frozen", FROZEN_TITLE));
		mongo.save(due("i-open", "Ordinary one"));
		NotificationService notifications = mock(NotificationService.class);

		new DueDateReminderJob(mongo, notifications, frozenIssues).remind();

		verify(notifications, never()).notifyDueSoon(
				org.mockito.ArgumentMatchers.argThat(issue -> "i-frozen".equals(issue.getId())), any());
		verify(notifications).notifyDueSoon(
				org.mockito.ArgumentMatchers.argThat(issue -> "i-open".equals(issue.getId())), any());
	}

	/**
	 * And the "already reminded" marker is not written for it either.
	 *
	 * <p>Excluding in the query rather than skipping in the loop is what buys this: a
	 * skipped issue whose {@code dueReminderFor} had been stamped would silently lose
	 * its reminder for good, so releasing the freeze would leave the assignee having
	 * been told nothing. Excluded, not deferred — and not consumed.
	 */
	@Test
	void aFrozenIssueIsNotMarkedAsAlreadyReminded() {
		mongo.save(due("i-frozen", FROZEN_TITLE));

		new DueDateReminderJob(mongo, mock(NotificationService.class), frozenIssues).remind();

		assertThat(mongo.findById("i-frozen", Issue.class).getDueReminderFor()).isNull();
	}

	// --- 4. the weekly summary, and the digest e-mail it renders -----------------

	/**
	 * One aggregation feeds two surfaces: the in-app page and the Monday digest
	 * mail. So an unscoped {@code highlights} did not only serve a frozen title, it
	 * put one in an HTML e-mail that nothing can recall.
	 */
	@Test
	void aFrozenIssueIsNeitherAHighlightNorAnUpcomingItem() {
		mongo.save(resolvedThisWeek("i-frozen", FROZEN_TITLE));
		mongo.save(resolvedThisWeek("i-open", "Ordinary one"));
		mongo.save(due("i-frozen-open", FROZEN_TITLE));

		WeeklySummaryService.WeeklySummary summary = weekly(frozenIssues).forUser(admin);

		assertThat(summary.team().highlights()).extracting(Issue::getTitle)
				.doesNotContain(FROZEN_TITLE).contains("Ordinary one");
		assertThat(summary.upcoming().items()).extracting(Issue::getId)
				.doesNotContain("i-frozen-open");
	}

	/** And the headline count matches the list under it. */
	@Test
	void theWeeklyCompletedCountDoesNotIncludeTheFrozenIssue() {
		mongo.save(resolvedThisWeek("i-frozen", FROZEN_TITLE));
		mongo.save(resolvedThisWeek("i-open", "Ordinary one"));

		assertThat(weekly(frozenIssues).forUser(admin).team().completed()).isEqualTo(1);
		assertThat(weekly(nothingFrozen).forUser(admin).team().completed()).isEqualTo(2);
	}

	// --- 5. the dashboard --------------------------------------------------------

	/** The dashboard hands back whole {@link Issue} entities and renders the titles. */
	@Test
	void aFrozenIssueIsAbsentFromTodaysTasksAndItsCount() {
		mongo.save(due("i-frozen", FROZEN_TITLE));
		mongo.save(due("i-open", "Ordinary one"));

		DashboardService.DashboardData data =
				dashboard(frozenIssues).dashboard(null, null, null, admin);

		assertThat(data.todayTasks()).extracting(Issue::getTitle).doesNotContain(FROZEN_TITLE);
		assertThat(data.todayCount()).isEqualTo(1);
	}

	// --- 6. the project reports --------------------------------------------------

	/**
	 * Counts only — no title reaches the response — but a frozen row still inflates
	 * every distribution, and the exclusion costs one call.
	 */
	@Test
	void theStateDistributionDoesNotCountTheFrozenIssue() {
		mongo.save(due("i-frozen", FROZEN_TITLE));
		mongo.save(due("i-open", "Ordinary one"));

		assertThat(reports(frozenIssues).issuesByState(PROJECT).values()).containsExactly(1L);
		assertThat(reports(nothingFrozen).issuesByState(PROJECT).values()).containsExactly(2L);
	}

	/**
	 * The created/resolved trend is the report's <em>other</em> query, and it needs
	 * its own assertion rather than sharing the one above.
	 *
	 * <p>Written because the mutation run caught it: disabling the exclusion on this
	 * query alone left every test in the suite green, since the only report assertion
	 * exercised {@code countBy}. Two queries in one file are two things that can be
	 * wrong, and a test that covers one of them says nothing about the other.
	 */
	@Test
	void theCreatedVersusResolvedTrendDoesNotCountTheFrozenIssue() {
		mongo.save(resolvedThisWeek("i-frozen", FROZEN_TITLE));
		mongo.save(resolvedThisWeek("i-open", "Ordinary one"));

		assertThat(resolvedTotal(reports(frozenIssues))).isEqualTo(1);
		assertThat(resolvedTotal(reports(nothingFrozen))).isEqualTo(2);
	}

	private static long resolvedTotal(ReportController controller) {
		return controller.createdVsResolved(PROJECT, 30).stream()
				.mapToLong(ReportController.TrendPoint::resolved).sum();
	}

	// --- helpers -----------------------------------------------------------------

	private BoardController board(FrozenIssues freeze) {
		AgileBoardRepository boards = mock(AgileBoardRepository.class);
		when(boards.findById("b-1")).thenReturn(Optional.of(AgileBoard.builder().id("b-1")
				.name("Board").type(AgileBoard.Type.KANBAN)
				.projectIds(new ArrayList<>(List.of(PROJECT)))
				.columns(new ArrayList<>(List.of(AgileBoard.Column.builder().name("Open")
						.states(new ArrayList<>(List.of("Open"))).build())))
				.build()));
		SprintRepository sprints = mock(SprintRepository.class);
		when(sprints.findByBoardIdOrderByStartDateDesc("b-1")).thenReturn(List.of());
		IssueRepository issues = mock(IssueRepository.class);
		when(issues.findByProjectId(any(), any())).thenReturn(new PageImpl<>(List.of(
				open("i-frozen", FROZEN_TITLE), open("i-open", "Ordinary one"))));
		return new BoardController(boards, sprints, issues, mock(IssueService.class), projects,
				mock(com.ahmadre.hinata.deletion.DeletionService.class), currentUser(),
				mock(com.ahmadre.hinata.team.TeamRepository.class),
				mock(com.ahmadre.hinata.moderation.ModerationService.class), freeze);
	}

	private WeeklySummaryService weekly(FrozenIssues freeze) {
		return new WeeklySummaryService(projects, mock(UserRepository.class), mongo, freeze);
	}

	private DashboardService dashboard(FrozenIssues freeze) {
		DashboardPrefsRepository prefs = mock(DashboardPrefsRepository.class);
		when(prefs.findById(any())).thenReturn(Optional.empty());
		return new DashboardService(projects, mock(UserRepository.class), mongo, freeze,
				mock(com.ahmadre.hinata.team.TeamService.class), prefs);
	}

	private ReportController reports(FrozenIssues freeze) {
		return new ReportController(mongo, currentUser(), projects, freeze);
	}

	private CurrentUser currentUser() {
		CurrentUser current = mock(CurrentUser.class);
		when(current.require()).thenReturn(admin);
		when(current.requireId()).thenReturn(admin.getId());
		return current;
	}

	private static List<String> titlesOn(BoardController.BoardView view) {
		return view.columns().stream()
				.flatMap(column -> column.issues().stream())
				.map(Issue::getTitle)
				.toList();
	}

	private static Issue open(String id, String title) {
		return Issue.builder().id(id).projectId(PROJECT).title(title)
				.readableId("HIN-" + id.toUpperCase(java.util.Locale.ROOT))
				.type(Issue.Type.TASK).state("Open").archived(false).rank(1)
				.numberInProject(1).createdAt(Instant.now()).updatedAt(Instant.now())
				.attachments(new ArrayList<>()).build();
	}

	/** Open, assigned to the caller, and due today — the "today's tasks" shape. */
	private static Issue due(String id, String title) {
		Issue issue = open(id, title);
		issue.setDueDate(LocalDate.now(ZoneOffset.UTC));
		issue.setAssigneeId("u-admin");
		issue.setAssigneeIds(new ArrayList<>(List.of("u-admin")));
		return issue;
	}

	private static Issue resolvedThisWeek(String id, String title) {
		Issue issue = open(id, title);
		issue.setState("Done");
		issue.setResolvedAt(Instant.now().minus(1, ChronoUnit.DAYS));
		issue.setAssigneeId("u-admin");
		issue.setAssigneeIds(new ArrayList<>(List.of("u-admin")));
		return issue;
	}

	private static Issue scheduled(String id, String title) {
		Issue issue = open(id, title);
		issue.setStartDate(LocalDate.now(ZoneOffset.UTC));
		issue.setDueDate(LocalDate.now(ZoneOffset.UTC).plusDays(3));
		return issue;
	}

	private static User user(String id, boolean isAdmin) {
		return User.builder().id(id).displayName(id).active(true)
				.roles(Set.of(isAdmin ? Role.ADMIN : Role.MEMBER)).build();
	}
}
