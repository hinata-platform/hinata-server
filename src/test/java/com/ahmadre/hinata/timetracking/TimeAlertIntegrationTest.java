package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget and estimate alerts against a real database (HIN-92): once per crossing, re-armed when
 * the time falls under a threshold again, to leads for a project and to assignees for an issue.
 *
 * <p>The clock stands still; entries carry the wall-clock {@code createdAt} of auditing, which is
 * after it, so every entry recorded here counts as moved since the last run.
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
class TimeAlertIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeAlerts alerts;
	@Autowired
	private UserRepository users;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TestClock clock;

	private User lead;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class, Notification.class,
				TimeReminderMark.class, ProjectTimeSettings.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		clock.set(TestClock.START);
		lead = person("lead");
		member = person("member");
		project = mongo.insert(Project.builder().key("APO").name("Apollo")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId()))).build());
	}

	@Test
	void aBudgetTellsItsLeadsOnceAtEightyAndOnceAtAHundred() {
		budget(600);
		record(480, null);

		alerts.run();
		alerts.run();
		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).singleElement()
				.extracting(Notification::getBody).isEqualTo("Apollo has used 80 % of its time budget (8 h of 10 h).");
		assertThat(alertsOf(member, Notification.Type.TIME_BUDGET_ALERT)).isEmpty();

		record(120, null);
		alerts.run();
		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).hasSize(2)
				.extracting(Notification::getBody).contains("Apollo has used 100 % of its time budget (10 h of 10 h).");
	}

	@Test
	void fromNothingPastBothThresholdsIsOneAlertAtTheHigherOne() {
		budget(600);
		record(700, null);

		alerts.run();

		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).singleElement()
				.extracting(Notification::getBody).asString().contains("100 %");
	}

	@Test
	void raisingTheBudgetArmsTheThresholdsAgain() {
		budget(600);
		record(600, null);
		alerts.run();
		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).hasSize(1);

		budget(1200);
		alerts.run();
		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).hasSize(1);

		record(360, null);
		alerts.run();
		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).hasSize(2)
				.extracting(Notification::getBody).contains("Apollo has used 80 % of its time budget (16 h of 20 h).");
	}

	@Test
	void theSumOfEstimatesAlertsLikeABudget() {
		issue("APO-1", 300, 0, null);
		issue("APO-2", 300, 0, null);
		record(480, null);

		alerts.run();

		assertThat(alertsOf(lead, Notification.Type.TIME_BUDGET_ALERT)).singleElement()
				.extracting(Notification::getBody)
				.isEqualTo("Apollo has used 80 % of its estimated time (8 h of 10 h).");
	}

	@Test
	void anIssuePastItsEstimateTellsItsAssigneeAndNamesNobody() {
		Issue issue = issue("APO-3", 60, 90, member);
		record(90, issue.getId());

		alerts.run();
		alerts.run();

		assertThat(alertsOf(member, Notification.Type.TIME_ESTIMATE_EXCEEDED)).singleElement()
				.satisfies(sent -> {
					assertThat(sent.getBody()).isEqualTo("APO-3 has reached 100 % of its estimate (1.5 h of 1 h).");
					assertThat(sent.getBody()).doesNotContain("member", "lead");
				});
		assertThat(alertsOf(lead, Notification.Type.TIME_ESTIMATE_EXCEEDED)).isEmpty();
	}

	@Test
	void twoRunsAtOnceAlertOnce() throws Exception {
		budget(600);
		record(600, null);

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Callable<Integer> run = () -> {
			start.await();
			return alerts.run();
		};
		Future<Integer> first = pool.submit(run);
		Future<Integer> second = pool.submit(run);
		start.countDown();
		assertThat(first.get() + second.get()).isEqualTo(1);
		pool.shutdown();
	}

	@Test
	void withThePolicyOffNothingIsMeasured() {
		budget(600);
		record(600, null);
		ServerSettings off = new ServerSettings();
		off.setTimeTracking(new ServerSettings.TimeTracking());
		off.getTimeTracking().setAlertsEnabled(false);
		settings.save(off);

		assertThat(alerts.run()).isZero();
		assertThat(notifications.count()).isZero();
		assertThat(mongo.count(new Query(), TimeReminderMark.class)).isZero();
	}

	private User person(String name) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.active(true).locale("en").build());
	}

	private void budget(int minutes) {
		mongo.upsert(Query.query(Criteria.where("projectId").is(project.getId())),
				new Update().set("budgetMinutes", minutes).set("updatedAt", clock.instant()), ProjectTimeSettings.class);
	}

	private Issue issue(String readableId, int estimate, int spent, User assignee) {
		return mongo.insert(Issue.builder().projectId(project.getId()).readableId(readableId)
				.numberInProject(Integer.parseInt(readableId.substring(4))).title(readableId).state("Open")
				.estimateMinutes(estimate).spentMinutes(spent)
				.assigneeIds(assignee == null ? new ArrayList<>() : new ArrayList<>(List.of(assignee.getId())))
				.build());
	}

	private void record(int minutes, String issueId) {
		workItems.save(WorkItem.builder().userId(member.getId()).projectId(project.getId()).issueId(issueId)
				.date(LocalDate.of(2026, 9, 7)).durationMinutes(minutes).build());
	}

	private List<Notification> alertsOf(User person, Notification.Type type) {
		return notifications.findAll().stream()
				.filter(sent -> person.getId().equals(sent.getUserId()) && sent.getType() == type)
				.toList();
	}
}
