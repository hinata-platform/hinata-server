package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Budget and estimate usage against a real database (HIN-152): sums over the whole project from
 * the covering indexes, the project's warning share, and a figure that follows every write path of
 * an entry at once rather than after the cache runs out.
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
class TimeUsageIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeUsageService usage;
	@Autowired
	private TimeUsageCache cache;
	@Autowired
	private TimeTrackingService entries;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TestClock clock;
	@Autowired
	private TimeUsageController controller;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private Project project;
	private Project other;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class,
				ProjectTimeSettings.class)) {
			mongo.remove(new Query(), type);
		}
		cache.forgetAll();
		settings.save(new ServerSettings());
		clock.set(TestClock.START);
		member = users.save(User.builder().username("member").displayName("member")
				.email("member@example.test").active(true).locale("en").build());
		project = project("APO", "Apollo");
		other = project("GEM", "Gemini");
	}

	@Test
	void aProjectSumsEveryEntryAndEveryOpenEstimate() {
		budget(project, 600, null);
		issue(project, "APO-1", 120, false);
		issue(project, "APO-2", 180, false);
		issue(project, "APO-3", 500, true);
		record(project, 90);
		record(project, 30);
		record(other, 999);

		TimeUsageService.ProjectUsage apollo = usage.ofProjects(List.of(project.getId())).getFirst();

		assertThat(apollo.budgetMinutes()).isEqualTo(600);
		assertThat(apollo.usedMinutes()).isEqualTo(120);
		assertThat(apollo.estimatedMinutes()).isEqualTo(300);
		assertThat(apollo.nearPercent()).isEqualTo(TimeAlerts.DEFAULT_BUDGET_PERCENT);
	}

	@Test
	void theWarningShareIsTheProjectsOwnAlertThreshold() {
		budget(project, 600, 70);

		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().nearPercent()).isEqualTo(70);
	}

	@Test
	void aProjectWithoutBudgetOrEntriesAnswersZerosAndNoBudget() {
		TimeUsageService.ProjectUsage empty = usage.ofProjects(List.of(other.getId())).getFirst();

		assertThat(empty.budgetMinutes()).isNull();
		assertThat(empty.usedMinutes()).isZero();
		assertThat(empty.estimatedMinutes()).isZero();
	}

	@Test
	void manyProjectsAreAnsweredInTheOrderAsked() {
		record(project, 60);
		record(other, 30);

		assertThat(usage.ofProjects(List.of(other.getId(), project.getId())))
				.extracting(TimeUsageService.ProjectUsage::projectId, TimeUsageService.ProjectUsage::usedMinutes)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(other.getId(), 30L),
						org.assertj.core.groups.Tuple.tuple(project.getId(), 60L));
	}

	@Test
	void anEntryWrittenThroughTheServiceShowsAtOnce() {
		Issue issue = issue(project, "APO-1", 120, false);
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isZero();

		LocalDate today = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
		WorkItem entry = entries.create(new TimeTrackingService.NewEntry(project.getId(), issue.getId(), 45,
				today, null, null, null, null, null, null), WorkItem.Source.APP, member);
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isEqualTo(45);

		entries.update(entry.getId(), new TimeTrackingService.WorkItemPatch(75, null, null, null, false, null,
				false, null, null, null), member);
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isEqualTo(75);

		entries.delete(entry.getId(), member);
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isZero();
	}

	@Test
	void aWriteBehindTheServicesBackShowsOnceTheCacheRunsOut() {
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isZero();
		record(project, 60);
		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isZero();

		clock.advance(TimeUsageCache.TTL.plus(Duration.ofSeconds(1)));

		assertThat(usage.ofProjects(List.of(project.getId())).getFirst().usedMinutes()).isEqualTo(60);
	}

	@Test
	void anIssueAnswersItsOwnEstimateAndSpentTime() {
		Issue issue = issue(project, "APO-1", 120, false);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())),
				new Update().set("spentMinutes", 100), Issue.class);

		TimeUsageService.IssueUsage of = usage.ofIssue(mongo.findById(issue.getId(), Issue.class));

		assertThat(of.estimateMinutes()).isEqualTo(120);
		assertThat(of.spentMinutes()).isEqualTo(100);
		assertThat(of.projectId()).isEqualTo(project.getId());
	}

	@Test
	void somebodyOutsideTheProjectIsRefusedAndLeftOutOfABatch() {
		User outsider = users.save(User.builder().username("outsider").displayName("outsider")
				.email("outsider@example.test").active(true).locale("en").build());
		Project own = mongo.insert(Project.builder().key("OWN").name("Own")
				.leadId(outsider.getId()).leadIds(new ArrayList<>(List.of(outsider.getId())))
				.memberIds(new ArrayList<>(List.of(outsider.getId()))).build());
		Issue issue = issue(project, "APO-1", 60, false);
		when(currentUser.require()).thenReturn(outsider);

		assertThatThrownBy(() -> controller.project(project.getId()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> controller.issue(issue.getId())).isInstanceOf(ApiException.class);
		// Asked about one project of their own, one they cannot see and one that does not exist:
		// only their own comes back, and nothing says which of the others exists.
		assertThat(controller.projects(List.of(project.getId(), own.getId(), "6a0000000000000000000000")))
				.extracting(TimeUsageService.ProjectUsage::projectId).containsExactly(own.getId());
	}

	@Test
	void aMemberReadsTheProjectAndItsIssue() {
		Issue issue = issue(project, "APO-1", 60, false);
		when(currentUser.require()).thenReturn(member);

		assertThat(controller.project(project.getId()).projectId()).isEqualTo(project.getId());
		assertThat(controller.issue("APO-1").estimateMinutes()).isEqualTo(60);
		assertThat(controller.issue(issue.getId()).issueId()).isEqualTo(issue.getId());
	}

	@Test
	void aBatchLargerThanAPageIsRefused() {
		when(currentUser.require()).thenReturn(member);
		List<String> many = java.util.stream.IntStream.range(0, TimeUsageService.BATCH_MAX + 1)
				.mapToObj(i -> "p" + i).toList();

		assertThatThrownBy(() -> controller.projects(many))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void theDashboardGetsTheFullestBudgetsItSeesFirst() {
		Project third = project("MER", "Mercury");
		budget(project, 600, null);
		budget(other, 100, null);
		budget(third, 1000, null);
		record(project, 300);
		record(other, 90);
		record(third, 100);
		User outsider = users.save(User.builder().username("out").displayName("out")
				.email("out@example.test").active(true).locale("en").build());
		Project hidden = mongo.insert(Project.builder().key("HID").name("Hidden")
				.leadId(outsider.getId()).leadIds(new ArrayList<>(List.of(outsider.getId())))
				.memberIds(new ArrayList<>(List.of(outsider.getId()))).build());
		budget(hidden, 10, null);
		record(hidden, 50);
		when(currentUser.require()).thenReturn(member);

		TimeUsageController.TopBudgets top = controller.top(2, null);

		assertThat(top.total()).isEqualTo(3);
		assertThat(top.items()).extracting(TimeUsageService.ProjectUsage::projectId)
				.containsExactly(other.getId(), project.getId());
		assertThat(top.names()).containsOnlyKeys(other.getId(), project.getId())
				.containsEntry(other.getId(), "Gemini");
		assertThat(controller.top(3, List.of(third.getId())).items())
				.extracting(TimeUsageService.ProjectUsage::projectId).containsExactly(third.getId());
	}

	private Project project(String key, String name) {
		return mongo.insert(Project.builder().key(key).name(name)
				.leadId(member.getId()).leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId()))).build());
	}

	private void budget(Project of, int minutes, Integer nearPercent) {
		mongo.upsert(Query.query(Criteria.where("projectId").is(of.getId())),
				new Update().set("budgetMinutes", minutes)
						.set("alertThresholds", nearPercent == null ? null
								: ProjectTimeSettings.AlertThresholds.builder().budgetPercent(nearPercent).build()),
				ProjectTimeSettings.class);
	}

	private Issue issue(Project of, String readableId, int estimate, boolean archived) {
		return mongo.insert(Issue.builder().projectId(of.getId()).readableId(readableId)
				.numberInProject(Integer.parseInt(readableId.substring(4))).title(readableId).state("Open")
				.estimateMinutes(estimate).archived(archived).assigneeIds(new ArrayList<>()).build());
	}

	private void record(Project of, int minutes) {
		mongo.insert(WorkItem.builder().userId(member.getId()).projectId(of.getId())
				.date(LocalDate.of(2026, 9, 7)).durationMinutes(minutes).build());
	}
}
