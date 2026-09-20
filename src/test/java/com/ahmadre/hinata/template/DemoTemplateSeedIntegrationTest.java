package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo workspace on an instance that has project templates switched on.
 *
 * <p>The pair is the point: a template whose issues carry rules and no dates at all, and a project
 * made from it whose issues carry the dates those rules work out to. Somebody opening a fresh
 * instance sees in one look what a paragraph of documentation has to explain.
 *
 * <p>Worth a test rather than a glance, because the seeder is the one place the copy runs without
 * a person watching: if it ever produced a template with dates, or an instance without them, the
 * first impression of the feature would be of something that does not work.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.project-templates.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class DemoTemplateSeedIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private ProjectRepository projects;

	@Test
	@DisplayName("the demo workspace holds a template and a project made from it")
	void theSeedShowsTheFeature() {
		Project template = projects.findByKeyIgnoreCase("EVENT").orElseThrow();
		Project instance = projects.findByKeyIgnoreCase("BFQ").orElseThrow();

		assertThat(template.isTemplate()).isTrue();
		assertThat(template.getEventDate()).isNull();
		assertThat(instance.isTemplate()).isFalse();
		assertThat(instance.getEventDate()).isNotNull();
	}

	@Test
	@DisplayName("the template carries rules and no dates")
	void theTemplateHasRulesOnly() {
		List<Issue> issues = issuesOf("EVENT");

		assertThat(issues).isNotEmpty();
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.getTitle()).isEqualTo("Book the room");
			assertThat(issue.getDueOffset()).isNotNull();
			assertThat(issue.getDueOffset().amount()).isEqualTo(-6);
			assertThat(issue.getDueDate()).isNull();
		});
		// Sub-tasks too, so the copy has a hierarchy to carry rather than a flat list.
		assertThat(issues).anySatisfy(issue ->
				assertThat(issue.getType()).isEqualTo(Issue.Type.SUBTASK));
	}

	@Test
	@DisplayName("the project made from it carries the same rules, resolved against its own date")
	void theInstanceHasDates() {
		Project instance = projects.findByKeyIgnoreCase("BFQ").orElseThrow();
		List<Issue> issues = issuesOf("BFQ");

		assertThat(issues).hasSameSizeAs(issuesOf("EVENT"));
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.getTitle()).isEqualTo("Book the room");
			assertThat(issue.getDueOffset()).isNotNull();
			assertThat(issue.getDueDate())
					.isEqualTo(instance.getEventDate().minusWeeks(6));
		});
		// And the one working-day deadline lands on a working day, which a calendar-day
		// offset of the same size would not guarantee.
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.getTitle()).isEqualTo("Send the poster to print");
			assertThat(issue.getDueDate()).isNotNull();
			assertThat(issue.getDueDate().getDayOfWeek().getValue()).isLessThan(6);
		});
	}

	private List<Issue> issuesOf(String key) {
		Project project = projects.findByKeyIgnoreCase(key).orElseThrow();
		return mongo.find(Query.query(Criteria.where("projectId").is(project.getId())), Issue.class);
	}
}
