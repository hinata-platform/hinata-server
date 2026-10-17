package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live budget alerts and the warnings a save returns (HIN-153), against a real database. The
 * measuring is called on the test's thread, so a test needs no wait for the async executor.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.alerts-enabled=true"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeBudgetLiveIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeBudgetLiveAlerts live;
	@Autowired
	private TimeBudgetGuard guard;
	@Autowired
	private TimeUsageCache cache;
	@Autowired
	private UserRepository users;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private SettingsService settings;

	private User lead;
	private User teamAdmin;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class, Notification.class,
				TimeMark.class, ProjectTimeSettings.class, Team.class, SprintTimeSettings.class,
				com.ahmadre.hinata.board.Sprint.class, com.ahmadre.hinata.board.AgileBoard.class)) {
			mongo.remove(new Query(), type);
		}
		cache.forgetAll();
		settings.save(new ServerSettings());
		lead = person("lead");
		teamAdmin = person("teamadmin");
		member = person("member");
		project = mongo.insert(Project.builder().key("APO").name("Apollo")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId()))).build());
		Team team = Team.builder().key("CORE").name("Core")
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(
						TeamMembership.builder().userId(teamAdmin.getId()).role(TeamRole.ADMIN).build(),
						TeamMembership.builder().userId(member.getId()).build())))
				.build();
		mongo.insert(team);
	}

	@Test
	void leadsAndTeamAdminsHearEachStageOnceAndNobodyElse() {
		budget(600, null);
		record(480, null);
		touch(null);
		touch(null);
		assertThat(bodies(lead)).containsExactly("Apollo has used 80 % of its time budget (8 h of 10 h).");
		assertThat(bodies(teamAdmin)).hasSize(1);
		assertThat(bodies(member)).isEmpty();

		record(180, null);
		touch(null);
		assertThat(bodies(lead)).hasSize(2).last().asString().contains("110 %");
		record(30, null);
		touch(null);
		assertThat(bodies(lead)).hasSize(2);
	}

	@Test
	void raisingTheBudgetArmsTheStagesAgain() {
		budget(600, null);
		record(600, null);
		touch(null);
		assertThat(bodies(lead)).hasSize(1);

		budget(1200, null);
		touch(null);
		record(400, null);
		touch(null);

		assertThat(bodies(lead)).hasSize(2).last().asString().contains("80 %").contains("16.7 h of 20 h");
	}

	@Test
	void openProjectsAndAnInstanceWithoutAlertsHearNothingLive() {
		budget(600, ProjectTimeSettings.LimitMode.OPEN);
		record(700, null);
		touch(null);
		assertThat(notifications.count()).isZero();

		budget(600, ProjectTimeSettings.LimitMode.WARN);
		ServerSettings off = new ServerSettings();
		off.setTimeTracking(new ServerSettings.TimeTracking());
		off.getTimeTracking().setAlertsEnabled(false);
		settings.save(off);
		touch(null);
		assertThat(notifications.count()).isZero();
	}

	@Test
	void anIssuePastItsEstimateTellsAssigneesAndLeadsAndNamesNobody() {
		Issue issue = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-1")
				.numberInProject(1).title("x").state("Open").estimateMinutes(60).spentMinutes(70)
				.assigneeIds(new ArrayList<>(List.of(member.getId()))).build());
		touch(issue.getId());

		assertThat(estimateBodies(member)).singleElement().asString().contains("APO-1").contains("110 %")
				.doesNotContain("member", "lead");
		assertThat(estimateBodies(lead)).hasSize(1);
	}

	@Test
	void theSaveIsToldWhatItDidAndAnOpenProjectIsNot() {
		budget(600, null);
		Issue issue = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-2")
				.numberInProject(2).title("y").state("Open").estimateMinutes(100).spentMinutes(90)
				.assigneeIds(new ArrayList<>()).build());
		WorkItem saved = record(540, issue.getId());

		assertThat(guard.warningsAfter(saved, lead))
				.extracting(TimeBudgetGuard.BudgetWarning::kind, TimeBudgetGuard.BudgetWarning::level,
						TimeBudgetGuard.BudgetWarning::percent)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(TimeBudgetGuard.Kind.BUDGET,
								TimeBudgetGuard.Level.NEAR, 90),
						org.assertj.core.groups.Tuple.tuple(TimeBudgetGuard.Kind.ESTIMATE,
								TimeBudgetGuard.Level.NEAR, 90));

		budget(600, ProjectTimeSettings.LimitMode.OPEN);
		mongo.updateFirst(Query.query(Criteria.where("projectId").is(project.getId())),
				new Update().set("estimateLimitMode", ProjectTimeSettings.LimitMode.OPEN), ProjectTimeSettings.class);
		assertThat(guard.warningsAfter(saved, lead)).isEmpty();
	}

	@Test
	void aSprintIsHeardByTheLeadsWhoSeeEveryProjectOfItsBoard() {
		budget(600000, null);
		com.ahmadre.hinata.board.Sprint sprint = sprintOn(List.of(project.getId()));
		Issue issue = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-7").numberInProject(7)
				.title("s").state("Open").sprintId(sprint.getId()).assigneeIds(new ArrayList<>()).build());
		record(540, issue.getId());
		touch(issue.getId());
		assertThat(bodies(lead)).singleElement().asString().contains("Sprint").contains("80 %");

		// A board that also spans a project the leads cannot see: its sum is not theirs to hear.
		Project hidden = mongo.insert(Project.builder().key("SEC").name("Secret").leadId("someone")
				.leadIds(new ArrayList<>(List.of("someone"))).memberIds(new ArrayList<>(List.of("someone"))).build());
		com.ahmadre.hinata.board.Sprint shared = sprintOn(List.of(project.getId(), hidden.getId()));
		Issue other = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-8").numberInProject(8)
				.title("t").state("Open").sprintId(shared.getId()).assigneeIds(new ArrayList<>()).build());
		record(540, other.getId());
		touch(other.getId());
		assertThat(bodies(lead)).hasSize(1);
	}

	private com.ahmadre.hinata.board.Sprint sprintOn(List<String> projectIds) {
		com.ahmadre.hinata.board.AgileBoard board = mongo.insert(com.ahmadre.hinata.board.AgileBoard.builder()
				.name("Board").projectIds(new ArrayList<>(projectIds)).build());
		com.ahmadre.hinata.board.Sprint sprint = mongo.insert(com.ahmadre.hinata.board.Sprint.builder()
				.boardId(board.getId()).name("Sprint " + board.getId().substring(0, 4))
				.startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 14)).build());
		mongo.insert(SprintTimeSettings.builder().sprintId(sprint.getId()).budgetMinutes(600).build());
		return sprint;
	}

	private User person(String name) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.active(true).locale("en").build());
	}

	private void budget(int minutes, ProjectTimeSettings.LimitMode mode) {
		mongo.upsert(Query.query(Criteria.where("projectId").is(project.getId())),
				new Update().set("budgetMinutes", minutes).set("budgetLimitMode", mode), ProjectTimeSettings.class);
		cache.forgetAll();
	}

	private WorkItem record(int minutes, String issueId) {
		cache.forgetAll();
		return mongo.insert(WorkItem.builder().userId(member.getId()).projectId(project.getId())
				.issueId(issueId).date(LocalDate.of(2026, 9, 7)).durationMinutes(minutes).build());
	}

	private void touch(String issueId) {
		live.measure(new TimeEntryWrites.Touched(Set.of(project.getId()),
				issueId == null ? Set.of() : Set.of(issueId)));
	}

	private List<String> bodies(User person) {
		return of(person, Notification.Type.TIME_BUDGET_ALERT);
	}

	private List<String> estimateBodies(User person) {
		return of(person, Notification.Type.TIME_ESTIMATE_REACHED);
	}

	private List<String> of(User person, Notification.Type type) {
		return notifications.findAll().stream()
				.filter(sent -> person.getId().equals(sent.getUserId()) && sent.getType() == type)
				.map(Notification::getBody).toList();
	}
}
