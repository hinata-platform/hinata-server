package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text a board searches issues by, against a real MongoDB. Every way an issue's key, title or
 * labels change has to reach it, or a card goes missing from a search that should find it.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueSearchTextIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private ProjectService projectService;
	@Autowired
	private IssueService issueService;
	@Autowired
	private IssueSearchTextBackfill backfill;
	@Autowired
	private IssueIndexCleanup cleanup;

	private Project project;

	@BeforeEach
	void seed() {
		mongo.getCollection("issues").deleteMany(new Document());
		mongo.getCollection("projects").deleteMany(new Document());
		project = projects.save(Project.builder().key("HIN").name("Hinata").build());
	}

	@Test
	void everySaveKeepsTheKeyTheTitleAndTheLabelsInLowerCase() {
		Issue issue = issues.save(issue(1, "HIN-1", "Fix the Login Screen", "Mobile", "UI"));
		assertThat(stored(issue)).isEqualTo("hin-1\nfix the login screen\nmobile\nui");

		issue.setTitle("Übersicht öffnen");
		issues.save(issue);

		assertThat(stored(issue)).isEqualTo("hin-1\nübersicht öffnen\nmobile\nui");
	}

	@Test
	void aLabelPulledFromTheProjectLeavesTheSearchText() {
		Issue issue = issues.save(issue(2, "HIN-2", "Tagged", "Mobile"));

		issueService.removeProjectLabel(project.getId(), "Mobile", null);

		assertThat(stored(issue)).isEqualTo("hin-2\ntagged");
	}

	@Test
	void aProjectsNewKeyReachesTheSearchText() {
		Issue issue = issues.save(issue(3, "OLD-3", "Re-keyed"));

		projectService.reKeyIssues(project.getId(), "HIN");

		assertThat(stored(issue)).isEqualTo("hin-3\nre-keyed");
	}

	@Test
	void theBackfillWritesWhatAnOlderServerSavedWithout() {
		Issue issue = issues.save(issue(4, "HIN-4", "Saved before", "Api"));
		withoutSearchText(issue);
		forgetTheBackfill();

		backfill.run(null);

		assertThat(stored(issue)).isEqualTo("hin-4\nsaved before\napi");
	}

	@Test
	void theBackfillAsksOnlyUntilItFilledEveryIssue() {
		forgetTheBackfill();
		backfill.run(null);
		Issue issue = issues.save(issue(5, "HIN-5", "Saved after"));
		withoutSearchText(issue);

		backfill.run(null);

		assertThat(stored(issue)).as("a start after a completed backfill reads no issue for it").isNull();
	}

	@Test
	void dropsTheIndexesOnlyWritesPaidFor() {
		mongo.indexOps(Issue.class).createIndex(new Index("state", Sort.Direction.ASC).named("state"));
		mongo.indexOps(Issue.class).createIndex(new Index().on("projectId", Sort.Direction.ASC)
				.on("rank", Sort.Direction.ASC).named("board_column"));

		cleanup.run(null);

		assertThat(mongo.indexOps(Issue.class).getIndexInfo()).extracting(IndexInfo::getName)
				.doesNotContain("state", "projectId", "board_column")
				.contains("board_by_state", "board_by_sprint", "board_by_dates", "board_by_assignee", "board_by_reporter",
						"board_by_label");
	}

	private void withoutSearchText(Issue issue) {
		mongo.getCollection("issues").updateOne(new Document("_id", new ObjectId(issue.getId())),
				new Document("$unset", new Document(IssueSearchText.FIELD, "")));
	}

	private void forgetTheBackfill() {
		mongo.getCollection(MigrationMarkers.COLLECTION)
				.deleteOne(new Document("_id", IssueSearchTextBackfill.MARKER_ID));
	}

	private Issue issue(long number, String readableId, String title, String... tags) {
		return Issue.builder().projectId(project.getId()).numberInProject(number).readableId(readableId)
				.title(title).state("Open").type(Issue.Type.TASK).priority(Issue.Priority.NORMAL)
				.tags(new ArrayList<>(List.of(tags))).assigneeIds(new ArrayList<>())
				.watcherIds(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build();
	}

	private String stored(Issue issue) {
		return mongo.getCollection("issues").find(new Document("_id", new ObjectId(issue.getId()))).first()
				.getString(IssueSearchText.FIELD);
	}
}
