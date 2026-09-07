package com.ahmadre.hinata.git;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code #time} in a commit message, end to end.
 *
 * <p>Before HIN-82 this added minutes straight onto {@code Issue.spentMinutes}
 * with no entry behind them — invisible in the timesheet, wiped out by the next
 * sync — and every command in a push was attributed to whoever had connected
 * the repository. Both are pinned here: the hours belong to the commit's
 * author, they exist as a real entry, and an author nobody recognizes is
 * skipped rather than credited to the connector.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(SmartCommitTimeIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class SmartCommitTimeIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private GitService git;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	/** The account that connected the repository — the actor a webhook hands in. */
	private User connector;
	/** The person who wrote the commit. */
	private User author;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"issue_activities", "notifications", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		connector = user("connector", true);
		author = user("author", true);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(connector.getId())
				.leadIds(new ArrayList<>(List.of(connector.getId())))
				.memberIds(new ArrayList<>(List.of(connector.getId(), author.getId())))
				.git(Project.Git.builder().provider("github").owner("hinata").repo("server")
						.connectedBy(connector.getId()).automation(Project.Automation.off())
						.build())
				.build());
		issue = issues.save(Issue.builder().projectId(project.getId()).readableId("HIN-42")
				.numberInProject(42).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name, boolean active) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(Role.MEMBER)).active(active).timezone("UTC")
				.build());
	}

	private void push(String message, String authorEmail, Instant committedAt) {
		git.applySmartCommits(new GitService.SmartCommit("abc1234def", message, authorEmail,
				committedAt), connector);
	}

	private int spentMinutes() {
		return issues.findById(issue.getId()).orElseThrow().getSpentMinutes();
	}

	// --- the happy path -------------------------------------------------------------

	@Test
	void timeInACommitBecomesAnEntryForItsAuthorNotForTheConnector() {
		push("HIN-42 #time 2h 30m fixed the redirect", author.getEmail(),
				Instant.parse("2026-09-06T09:00:00Z"));

		assertThat(workItems.findAll()).singleElement().satisfies(entry -> {
			assertThat(entry.getUserId()).as("the commit author, never the repo connector")
					.isEqualTo(author.getId());
			assertThat(entry.getDurationMinutes()).isEqualTo(150);
			assertThat(entry.getIssueId()).isEqualTo(issue.getId());
			assertThat(entry.getProjectId()).isEqualTo(project.getId());
			assertThat(entry.getSource()).isEqualTo(WorkItem.Source.SMART_COMMIT);
			assertThat(entry.getDate()).isEqualTo(LocalDate.of(2026, 9, 6));
			assertThat(entry.getDescription()).isEqualTo("Smart commit abc1234: HIN-42 #time 2h 30m fixed the redirect");
		});
		assertThat(spentMinutes()).as("the counter is derived from the entry, not bumped past it")
				.isEqualTo(150);
	}

	/** A commit clock running ahead of ours must not create an entry in the future. */
	@Test
	void aCommitDatedAheadOfNowIsBookedToToday() {
		push("HIN-42 #time 1h", author.getEmail(), Instant.parse("2026-09-11T09:00:00Z"));

		assertThat(workItems.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getDate()).isEqualTo(TODAY));
	}

	/** No timestamp in the payload: the entry lands on the author's today. */
	@Test
	void aCommitWithoutATimestampLandsOnToday() {
		push("HIN-42 #time 45m", author.getEmail(), null);

		assertThat(workItems.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getDate()).isEqualTo(TODAY));
	}

	// --- everything that must NOT be written -------------------------------------------

	@Test
	void anAuthorNobodyRecognizesIsSkippedAndTheCounterStaysPut() {
		push("HIN-42 #time 2h", "stranger@elsewhere.example", NOW);

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).as("no silent bump behind the entries' back").isZero();
	}

	@Test
	void aPayloadWithoutAnAuthorAddressIsSkipped() {
		push("HIN-42 #time 2h", null, NOW);

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).isZero();
	}

	@Test
	void aDeactivatedAccountIsNotAnAuthor() {
		User gone = user("gone", false);
		project.getMemberIds().add(gone.getId());
		projects.save(project);

		push("HIN-42 #time 2h", gone.getEmail(), NOW);

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).isZero();
	}

	/** Someone with an account but no access to the project cannot be booked time on it. */
	@Test
	void anAuthorWhoIsNotAMemberOfTheProjectIsSkipped() {
		User outsider = user("outsider", true);

		push("HIN-42 #time 2h", outsider.getEmail(), NOW);

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).isZero();
	}

	/** An ancient commit falls outside the date window; it is skipped, not a 500 on the webhook. */
	@Test
	void aCommitOlderThanTheWindowIsSkippedRatherThanBreakingTheHook() {
		push("HIN-42 #time 2h", author.getEmail(), Instant.parse("2020-01-01T09:00:00Z"));

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).isZero();
	}

	@Test
	void smartCommitsTurnedOffMeansNothingIsLogged() {
		project.getGit().setAutomation(Project.Automation.builder().smartCommits(false).build());
		projects.save(project);

		push("HIN-42 #time 2h", author.getEmail(), NOW);

		assertThat(workItems.findAll()).isEmpty();
		assertThat(spentMinutes()).isZero();
	}

	/**
	 * The author line of a commit is text anyone with push access can write, so
	 * it cannot be what decides where an entry may land. A push that reaches an
	 * issue in a project it has nothing to do with writes nothing, even when the
	 * name it credits belongs to a member of that other project.
	 */
	@Test
	void aPushCannotBookTimeOntoAnIssueItCannotReach() {
		User insider = user("insider", true);
		Project other = projects.save(Project.builder().key("SEC").name("Security")
				.leadId(insider.getId())
				.leadIds(new ArrayList<>(List.of(insider.getId())))
				.memberIds(new ArrayList<>(List.of(insider.getId())))
				.git(Project.Git.builder().provider("github").owner("hinata").repo("secrets")
						.connectedBy(insider.getId()).automation(Project.Automation.off())
						.build())
				.build());
		Issue theirs = issues.save(Issue.builder().projectId(other.getId()).readableId("SEC-12")
				.numberInProject(12).title("Rotate the keys").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());

		// The push is signed for the HIN repository; the connector is not in SEC.
		push("SEC-12 #time 8h", insider.getEmail(), NOW);

		assertThat(workItems.findAll()).as("no hours forged into a project the push cannot see")
				.isEmpty();
		assertThat(issues.findById(theirs.getId()).orElseThrow().getSpentMinutes()).isZero();
	}

	/**
	 * Crediting somebody else has to leave a trace, because the credit itself is
	 * unverifiable: whoever pushed chose the name.
	 */
	@Test
	void bookingTimeOntoSomebodyElseIsAuditedUnderBothNames() {
		push("HIN-42 #time 2h", author.getEmail(), NOW);

		Document entry = mongo.getCollection("audit_log")
				.find(new Document("action", "TIME_ENTRY_CREATED_FOR")).first();
		assertThat(entry).as("a booking onto another account is findable afterwards").isNotNull();
		assertThat(entry.getString("actorId")).as("who pushed").isEqualTo(connector.getId());
		assertThat(entry.getString("targetId")).as("who was credited").isEqualTo(author.getId());
		Document meta = entry.get("metadata", Document.class);
		assertThat(meta.getString("issue")).isEqualTo("HIN-42");
		assertThat(meta.getString("commit")).isEqualTo("abc1234");
		assertThat(meta.getString("minutes")).isEqualTo("120");
	}

	/** Logging your own hours from your own push is the ordinary case, not an event. */
	@Test
	void bookingYourOwnTimeIsNotAudited() {
		push("HIN-42 #time 2h", connector.getEmail(), NOW);

		assertThat(workItems.findAll()).singleElement()
				.satisfies(item -> assertThat(item.getUserId()).isEqualTo(connector.getId()));
		assertThat(mongo.getCollection("audit_log")
				.countDocuments(new Document("action", "TIME_ENTRY_CREATED_FOR"))).isZero();
	}

	/** Two commands in one message: the comment is the connector's, the time is the author's. */
	@Test
	void onlyTheTimeIsAttributedToTheAuthor() {
		push("HIN-42 #time 30m #comment shipped", author.getEmail(), NOW);

		assertThat(workItems.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getUserId()).isEqualTo(author.getId()));
		assertThat(mongo.getCollection("issue_comments").countDocuments())
				.as("the comment still comes from the account that connected the repo")
				.isEqualTo(1);
		assertThat(mongo.getCollection("issue_comments").find().first().getString("authorId"))
				.isEqualTo(connector.getId());
	}
}
