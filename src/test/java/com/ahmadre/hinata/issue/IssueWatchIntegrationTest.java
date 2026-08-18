package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.IssueDigestService;
import com.ahmadre.hinata.notification.IssueMailDigest;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.project.ProjectUpdateRequest;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Watching an issue against a real MongoDB.
 *
 * <p>Everything under test here is a <em>persistence</em> behaviour, which is
 * why it is not mocked: whether two simultaneous subscribers both survive is a
 * property of {@code $addToSet}, whether a page of watched issues is complete is
 * a property of the query, and whether revoking access really unsubscribes
 * someone is a property of an {@code updateMulti} that has to match the right
 * rows. None of those can be observed against a stubbed template.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		// This test writes exactly the rows it wants to reason about; a seeded
		// workspace would drown them.
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueWatchIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueWatchService watching;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private ProjectService projectService;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamService teamService;
	@Autowired
	private UserRepository users;
	@Autowired
	private IssueDigestService digests;

	private User member;
	private User outsider;
	private User teamMember;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "notifications",
				"issue_activities", "issue_mail_digests")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = users.save(User.builder().email("member@example.org").username("member")
				.displayName("Member").roles(Set.of(Role.MEMBER)).active(true).build());
		outsider = users.save(User.builder().email("outsider@example.org").username("outsider")
				.displayName("Outsider").roles(Set.of(Role.MEMBER)).active(true).build());
		teamMember = users.save(User.builder().email("team@example.org").username("team")
				.displayName("Team member").roles(Set.of(Role.MEMBER)).active(true).build());
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(member.getId()).leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId())))
				.build());
		issue = issueRepository.save(Issue.builder().projectId(project.getId())
				.readableId("HIN-1").numberInProject(1).title("Login bug").state("Open")
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>())
				.build());
	}

	private List<String> watchersOf(String issueId) {
		return issueRepository.findById(issueId).map(Issue::getWatcherIds).orElseThrow();
	}

	/** A team owning the project, with [user] granted every project it owns. */
	private Team teamGranting(User user) {
		return teams.save(Team.builder().key("SQD").name("Squad").createdBy(member.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(TeamMembership.builder()
						.userId(user.getId()).role(TeamRole.MEMBER).access(ProjectAccess.all())
						.build())))
				.build());
	}

	// --- access is a precondition ---------------------------------------------

	@Test
	void aNonMemberCannotWatchAndNeverLandsInTheList() {
		// The status is part of the contract, not decoration: a refusal that
		// degraded to 404 would keep an "it threw" assertion green while telling
		// the caller the issue does not exist, and the app routes the two
		// differently. Pin it.
		assertThatThrownBy(() -> watching.watch(issue.getId(), outsider))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

		assertThat(watchersOf(issue.getId())).isEmpty();
	}

	/** Access through a team is access, so it is enough to subscribe. */
	@Test
	void aTeamGrantIsEnoughToWatch() {
		teamGranting(teamMember);
		// The grant is materialized into project membership by the team service; a
		// bare grant must work on its own, so the project list is left untouched.

		watching.watch(issue.getId(), teamMember);

		assertThat(watchersOf(issue.getId())).containsExactly(teamMember.getId());
	}

	// --- idempotence and concurrency ------------------------------------------

	@Test
	void watchingTwiceCreatesNoSecondEntry() {
		watching.watch(issue.getId(), member);
		watching.watch(issue.getId(), member);

		assertThat(watchersOf(issue.getId())).containsExactly(member.getId());
	}

	@Test
	void unwatchingIsIdempotentToo() {
		watching.watch(issue.getId(), member);
		watching.unwatch(issue.getId(), member);
		watching.unwatch(issue.getId(), member);

		assertThat(watchersOf(issue.getId())).isEmpty();
	}

	/**
	 * The reason the write is an {@code $addToSet} and not a read of the list
	 * followed by a save of it: with read-modify-write, every one of these
	 * requests would save the list it read and all but one watcher would vanish.
	 */
	@Test
	void simultaneousSubscribersAllSurvive() throws Exception {
		List<User> crowd = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			crowd.add(users.save(User.builder().email("u" + i + "@example.org").username("u" + i)
					.displayName("User " + i).roles(Set.of(Role.MEMBER)).active(true).build()));
		}
		project.getMemberIds().addAll(crowd.stream().map(User::getId).toList());
		projects.save(project);

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try {
			for (User each : crowd) {
				pool.submit(() -> {
					start.await();
					watching.watch(issue.getId(), each);
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(watchersOf(issue.getId()))
				.containsExactlyInAnyOrderElementsOf(crowd.stream().map(User::getId).toList());
	}

	// --- revocation cleans up --------------------------------------------------

	@Test
	void removingAMemberPullsThemFromEveryWatcherListInTheProject() {
		User second = users.save(User.builder().email("second@example.org").username("second")
				.displayName("Second").roles(Set.of(Role.MEMBER)).active(true).build());
		project.getMemberIds().add(second.getId());
		projects.save(project);
		watching.watch(issue.getId(), second);
		watching.watch(issue.getId(), member);
		assertThat(watchersOf(issue.getId())).contains(second.getId());

		projectService.applyUpdate(project.getId(), memberUpdate(List.of(member.getId())), member);

		assertThat(watchersOf(issue.getId()))
				.as("the removed member is gone, the remaining one is untouched")
				.containsExactly(member.getId());
	}

	@Test
	void revokingATeamGrantUnsubscribesTheGrantedUser() {
		Team team = teamGranting(teamMember);
		// Materialize the grant into project membership, as attaching does.
		teamService.attachProjects(team, member, List.of(project.getId()));
		watching.watch(issue.getId(), teamMember);
		assertThat(watchersOf(issue.getId())).contains(teamMember.getId());

		teamService.removeMember(teams.findById(team.getId()).orElseThrow(), member,
				teamMember.getId());

		assertThat(watchersOf(issue.getId())).isEmpty();
	}

	@Test
	void revocationAlsoThrowsAwayTheQueuedMail() {
		User second = users.save(User.builder().email("second@example.org").username("second")
				.displayName("Second").roles(Set.of(Role.MEMBER)).active(true).build());
		project.getMemberIds().add(second.getId());
		projects.save(project);
		watching.watch(issue.getId(), second);
		digests.queue(issue, second, List.of(new com.ahmadre.hinata.notification.FieldChange(
				com.ahmadre.hinata.notification.IssueChangeDiff.STATE, "Open", "Done")));
		assertThat(digests.hasPending(second.getId(), issue.getId())).isTrue();

		projectService.applyUpdate(project.getId(), memberUpdate(List.of(member.getId())), member);

		assertThat(digests.hasPending(second.getId(), issue.getId())).isFalse();
	}

	@Test
	void unwatchingThrowsAwayTheQueuedMail() {
		watching.watch(issue.getId(), member);
		digests.queue(issue, member, List.of(new com.ahmadre.hinata.notification.FieldChange(
				com.ahmadre.hinata.notification.IssueChangeDiff.STATE, "Open", "Done")));

		watching.unwatch(issue.getId(), member);

		assertThat(mongo.findAll(IssueMailDigest.class)).isEmpty();
	}

	// --- reading back ----------------------------------------------------------

	@Test
	void theWatchedListOnlyShowsIssuesFromProjectsTheUserCanStillSee() {
		Project other = projects.save(Project.builder().key("OTH").name("Other")
				.leadId(outsider.getId()).leadIds(new ArrayList<>(List.of(outsider.getId())))
				.memberIds(new ArrayList<>(List.of(outsider.getId(), member.getId())))
				.build());
		Issue foreign = issueRepository.save(Issue.builder().projectId(other.getId())
				.readableId("OTH-1").numberInProject(1).title("Other bug").state("Open")
				.watcherIds(new ArrayList<>(List.of(member.getId())))
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>())
				.dependsOnIds(new ArrayList<>()).build());
		watching.watch(issue.getId(), member);
		assertThat(watching.watchedBy(member, 0, 20).getContent())
				.extracting(Issue::getId).contains(foreign.getId());

		// Member loses the other project.
		other.getMemberIds().remove(member.getId());
		projects.save(other);

		Page<Issue> page = watching.watchedBy(member, 0, 20);
		assertThat(page.getContent()).extracting(Issue::getId).containsExactly(issue.getId());
		assertThat(page.getTotalElements())
				.as("the count must agree with the page, not with the raw subscription list")
				.isEqualTo(1);
	}

	@Test
	void anArchivedIssueDropsOutOfTheWatchedList() {
		watching.watch(issue.getId(), member);

		issues.setArchived(issue.getId(), true, member);

		assertThat(watching.watchedBy(member, 0, 20).getContent()).isEmpty();
	}

	@Test
	void theWatchedListPagesDeterministically() {
		for (int i = 2; i <= 7; i++) {
			issueRepository.save(Issue.builder().projectId(project.getId())
					.readableId("HIN-" + i).numberInProject(i).title("Issue " + i).state("Open")
					.watcherIds(new ArrayList<>(List.of(member.getId())))
					.assigneeIds(new ArrayList<>()).tags(new ArrayList<>())
					.dependsOnIds(new ArrayList<>()).build());
		}
		watching.watch(issue.getId(), member);

		List<String> first = watching.watchedBy(member, 0, 3).getContent()
				.stream().map(Issue::getId).toList();
		List<String> second = watching.watchedBy(member, 1, 3).getContent()
				.stream().map(Issue::getId).toList();

		assertThat(first).hasSize(3);
		assertThat(second).hasSize(3).doesNotContainAnyElementsOf(first);
	}

	/** Only what the request names is replaced; the rest of the project stands. */
	private ProjectUpdateRequest memberUpdate(List<String> memberIds) {
		return new ProjectUpdateRequest(null, null, null, null, List.of(member.getId()), memberIds,
				null, null, null, null, null, null);
	}
}
