package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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
 * The sprint insights' velocity: what a past sprint committed is what it held when it closed, not
 * what is left in it after its unfinished work moved on, and a reader counts only the projects they
 * see.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SprintReportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SprintService sprints;
	@Autowired
	private UserRepository users;

	private User lead;
	private Project project;
	private Project hidden;
	private AgileBoard board;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, Sprint.class, AgileBoard.class,
				com.ahmadre.hinata.issue.IssueActivity.class)) {
			mongo.remove(new Query(), type);
		}
		lead = users.save(User.builder().username("lead").displayName("Lead").email("lead@example.test")
				.roles(Set.of(Role.MEMBER)).active(true).locale("en").build());
		project = mongo.insert(Project.builder().key("APO").name("Apollo").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId()))).build());
		hidden = mongo.insert(Project.builder().key("SEC").name("Secret").leadId("someone")
				.leadIds(new ArrayList<>(List.of("someone"))).memberIds(new ArrayList<>(List.of("someone")))
				.build());
		board = mongo.insert(AgileBoard.builder().name("Board").ownerId(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId(), hidden.getId()))).build());
	}

	@Test
	void aClosedSprintKeepsWhatItCommittedAfterItsOpenWorkMovedOn() {
		Sprint done = sprint("Sprint 1");
		issue(project, done, 5, "Done");
		issue(project, done, 3, "Open");
		issue(hidden, done, 8, "Done");

		sprints.complete(done.getId(), "backlog", lead);
		Sprint running = sprint("Sprint 2");

		SprintService.VelocityPoint point = sprints.report(running.getId(), lead).velocity().getFirst();
		assertThat(point.committed()).isEqualTo(8);
		assertThat(point.completed()).isEqualTo(5);
		assertThat(sprints.report(running.getId(), lead).summary().avgVelocity()).isEqualTo(5);
	}

	@Test
	void aSprintClosedBeforeTheSnapshotCountsTheWorkItsCompletionMovedOut() {
		Sprint old = sprint("Sprint 0");
		issue(project, old, 5, "Done");
		Issue carried = issue(project, null, 3, "Open");
		old.setArchived(true);
		mongo.save(old);
		mongo.insert(com.ahmadre.hinata.issue.IssueActivity.builder().issueId(carried.getId())
				.field(com.ahmadre.hinata.issue.IssueActivity.Field.SPRINT).fromValue(old.getId()).toValue(null)
				.createdAt(old.getEndDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plusSeconds(3600))
				.build());
		Sprint running = sprint("Sprint 1");

		SprintService.VelocityPoint point = sprints.report(running.getId(), lead).velocity().getFirst();
		assertThat(point.committed()).isEqualTo(8);
		assertThat(point.completed()).isEqualTo(5);
	}

	private Sprint sprint(String name) {
		return mongo.insert(Sprint.builder().boardId(board.getId()).name(name)
				.startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 14)).build());
	}

	private Issue issue(Project in, Sprint sprint, int points, String state) {
		return mongo.insert(Issue.builder().projectId(in.getId()).readableId(in.getKey() + "-" + points + state)
				.numberInProject(points).title("x").state(state).storyPoints(points)
				.sprintId(sprint == null ? null : sprint.getId()).assigneeIds(new ArrayList<>()).build());
	}
}
