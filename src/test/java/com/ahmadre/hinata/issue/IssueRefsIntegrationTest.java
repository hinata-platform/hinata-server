package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /issues/refs}: the key and title of the issues a form holds only ids of, for the
 * caller's eyes only — the issues of projects they reach and those shared with them, nothing
 * else, and never more than the cap.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueRefsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	private User member;
	private Project own;
	private Project foreign;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = users.save(User.builder().email("member@example.org").username("member")
				.displayName("member").roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").build());
		User other = users.save(User.builder().email("other@example.org").username("other")
				.displayName("other").roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").build());
		own = project("OWN", member);
		foreign = project("OTH", other);
	}

	private Project project(String key, User leader) {
		return projects.save(Project.builder().key(key).name(key).leadId(leader.getId())
				.leadIds(new ArrayList<>(List.of(leader.getId())))
				.memberIds(new ArrayList<>(List.of(leader.getId()))).build());
	}

	private Issue issue(Project in, int number, List<String> participants) {
		return issueRepository.save(Issue.builder().projectId(in.getId())
				.readableId(in.getKey() + "-" + number).numberInProject(number).title("Issue " + number)
				.state("Open").spentMinutes(0).watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.participantIds(new ArrayList<>(participants)).tags(new ArrayList<>())
				.dependsOnIds(new ArrayList<>()).build());
	}

	@Test
	void namesTheIssuesOfReachableProjectsAndNothingElse() {
		Issue mine = issue(own, 1, List.of());
		Issue hidden = issue(foreign, 1, List.of());

		List<IssueService.IssueRef> refs = issues.refs(List.of(mine.getId(), hidden.getId(), "unknown"), member);

		assertThat(refs).extracting(IssueService.IssueRef::readableId).containsExactly("OWN-1");
		assertThat(refs.getFirst().title()).isEqualTo("Issue 1");
	}

	@Test
	void anIssueSharedWithTheCallerIsNamedThoughItsProjectIsNot() {
		Issue shared = issue(foreign, 2, List.of(member.getId()));

		assertThat(issues.refs(List.of(shared.getId()), member))
				.extracting(IssueService.IssueRef::readableId).containsExactly("OTH-2");
	}

	@Test
	void neverMoreThanTheCap() {
		List<String> ids = new ArrayList<>();
		for (int number = 1; number <= 25; number++) {
			ids.add(issue(own, number, List.of()).getId());
		}

		assertThat(issues.refs(ids, member)).hasSize(20);
	}

	@Test
	void noIdsAsksNothing() {
		assertThat(issues.refs(List.of(), member)).isEmpty();
		assertThat(issues.refs(null, member)).isEmpty();
	}
}
