package com.ahmadre.hinata.report;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The numbers {@link ReportService} answers with, and the bounds it answers
 * within. Who may ask is pinned next door, in
 * {@code timetracking.TimeTrackingAccessIntegrationTest}.
 *
 * <p>Every report here was rewritten to let Mongo do the counting, or to read
 * back only the field being counted. The point of this class is that none of
 * the answers moved when the arithmetic did.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ReportNumbersIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private ReportService reports;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private UserRepository users;

	private User admin;
	private Project project;

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		admin = users.save(User.builder().email("admin@example.org").username("admin")
				.displayName("admin").roles(Set.of(Role.ADMIN)).active(true).build());
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(admin.getId()).leadIds(new ArrayList<>(List.of(admin.getId())))
				.memberIds(new ArrayList<>(List.of(admin.getId()))).build());
	}

	private Issue issue(int number, String state, Issue.Priority priority, String assigneeId,
			Instant createdAt, Instant resolvedAt) {
		Issue saved = issues.save(Issue.builder().projectId(project.getId())
				.readableId("HIN-" + number).numberInProject(number).title("Issue " + number)
				.state(state).priority(priority).assigneeId(assigneeId).spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
		// createdAt is written by auditing, resolvedAt only on a real transition —
		// both are set here directly so the trend has something to bucket. Through
		// the template, so the String id is converted to the ObjectId it is stored
		// as; a raw filter on the String matches nothing and says so with silence.
		Update stamps = new Update().set("createdAt", createdAt);
		if (resolvedAt != null) {
			stamps.set("resolvedAt", resolvedAt);
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())), stamps, Issue.class);
		return saved;
	}

	private void log(int minutes, LocalDate day) {
		workItems.save(WorkItem.builder().projectId(project.getId()).userId(admin.getId())
				.date(day).durationMinutes(minutes).activityType("Development").build());
	}

	// --- distributions ---------------------------------------------------------

	@Test
	void theDistributionsCountEveryIssueOnTheFieldTheyName() {
		Instant now = Instant.now();
		issue(1, "Open", Issue.Priority.MAJOR, admin.getId(), now, null);
		issue(2, "Open", Issue.Priority.MINOR, null, now, null);
		issue(3, "Done", Issue.Priority.MAJOR, admin.getId(), now, now);

		assertThat(reports.issuesByState(project.getId(), admin))
				.containsOnly(org.assertj.core.api.Assertions.entry("Open", 2L),
						org.assertj.core.api.Assertions.entry("Done", 1L));
		assertThat(reports.issuesByPriority(project.getId(), admin))
				.containsOnly(org.assertj.core.api.Assertions.entry("MAJOR", 2L),
						org.assertj.core.api.Assertions.entry("MINOR", 1L));
		assertThat(reports.issuesByAssignee(project.getId(), admin))
				.as("an issue nobody owns counts under a name, not under null")
				.containsOnly(org.assertj.core.api.Assertions.entry(admin.getId(), 2L),
						org.assertj.core.api.Assertions.entry("unassigned", 1L));
	}

	// --- the trend --------------------------------------------------------------

	@Test
	void theTrendBucketsEachIssueOnceOntoItsOwnDay() {
		Instant twoDaysAgo = TODAY.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
				.plus(9, ChronoUnit.HOURS);
		Instant yesterday = TODAY.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
				.plus(9, ChronoUnit.HOURS);
		issue(1, "Done", Issue.Priority.MAJOR, null, twoDaysAgo, yesterday);
		issue(2, "Open", Issue.Priority.MAJOR, null, twoDaysAgo, null);
		// Outside the window: counted by neither column.
		issue(3, "Open", Issue.Priority.MAJOR, null,
				TODAY.minusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant(), null);

		List<ReportService.TrendPoint> trend = reports.createdVsResolved(project.getId(), 7, admin);

		assertThat(trend).hasSize(7);
		assertThat(trend.getFirst().date()).isEqualTo(TODAY.minusDays(6));
		assertThat(trend.getLast().date()).isEqualTo(TODAY);
		assertThat(trend).anySatisfy(point -> {
			assertThat(point.date()).isEqualTo(TODAY.minusDays(2));
			assertThat(point.created()).isEqualTo(2);
			assertThat(point.resolved()).isZero();
		});
		assertThat(trend).anySatisfy(point -> {
			assertThat(point.date()).isEqualTo(TODAY.minusDays(1));
			assertThat(point.created()).isZero();
			assertThat(point.resolved()).isEqualTo(1);
		});
		assertThat(trend.stream().mapToLong(ReportService.TrendPoint::created).sum())
				.as("the issue from six weeks ago is outside the window").isEqualTo(2);
	}

	@Test
	void theTrendWindowIsCappedRatherThanTrusted() {
		assertThat(reports.createdVsResolved(project.getId(), 10_000, admin))
				.hasSize(ReportService.MAX_TREND_DAYS);
		assertThat(reports.createdVsResolved(project.getId(), 0, admin)).hasSize(1);
	}

	// --- time per project -------------------------------------------------------

	@Test
	void timePerProjectSumsTheWindowAndNothingElse() {
		log(30, TODAY);
		log(45, TODAY.minusDays(1));
		log(60, TODAY.minusDays(10));

		assertThat(reports.timePerProject(TODAY.minusDays(2), TODAY, admin))
				.containsExactly(org.assertj.core.api.Assertions.entry(project.getId(), 75));
	}

	/**
	 * Without a bound, one request could ask for every year at once — the whole
	 * collection, in memory, to produce one number per project.
	 */
	@Test
	void aTimeReportRefusesAWindowWiderThanAYear() {
		assertThatThrownBy(() -> reports.timePerProject(
				TODAY.minusDays(ReportService.MAX_TIME_RANGE_DAYS + 1), TODAY, admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.time.invalidRange");
		assertThatThrownBy(() -> reports.timePerActivity(project.getId(),
				TODAY.minusDays(ReportService.MAX_TIME_RANGE_DAYS + 1), TODAY, admin))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.time.invalidRange");
		assertThatThrownBy(() -> reports.timePerProject(TODAY, TODAY.minusDays(1), admin))
				.isInstanceOf(ApiException.class);

		assertThat(reports.timePerProject(TODAY.minusDays(ReportService.MAX_TIME_RANGE_DAYS),
				TODAY, admin)).as("a year is still allowed").isEmpty();
	}
}
