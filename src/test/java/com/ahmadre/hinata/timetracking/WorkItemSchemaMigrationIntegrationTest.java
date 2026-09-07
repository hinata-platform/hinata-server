package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
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
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time backfill that lets {@code spentMinutes} become a derived number
 * without losing the time it already held.
 *
 * <p>Before 2.0 a {@code #time} smart commit bumped the counter directly and
 * wrote no entry. Once the counter is the sum of the entries, that time would
 * disappear on the first sync — so the migration books the difference as a
 * {@link WorkItem.Source#LEGACY} entry belonging to nobody. The two properties
 * that matter are asserted here: it never invents time that is already
 * accounted for, and running it again changes nothing.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class WorkItemSchemaMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private WorkItemSchemaMigration migration;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issues;

	/** {@code numberInProject} is unique per project, so it counts up with the issues. */
	private int nextNumber = 1;

	@BeforeEach
	void clear() {
		// The marker too: the runner writes it at boot, and every test here wants
		// to watch a first run rather than the no-op that follows one.
		for (String collection : List.of("issues", "work_items", WorkItemSchemaMigration.MARKERS)) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		nextNumber = 1;
	}

	private Issue issueWith(String key, int spentMinutes) {
		return issues.save(Issue.builder().projectId("p1").readableId(key)
				.numberInProject(nextNumber++)
				.title(key).state("Open").spentMinutes(spentMinutes)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private void tracked(Issue on, int minutes) {
		workItems.save(WorkItem.builder().issueId(on.getId()).projectId(on.getProjectId())
				.userId("u1").date(java.time.LocalDate.now()).durationMinutes(minutes).build());
	}

	private List<WorkItem> legacyEntries() {
		return workItems.findAll().stream()
				.filter(item -> item.getSource() == WorkItem.Source.LEGACY).toList();
	}

	// --- the remainder ------------------------------------------------------------

	@Test
	void aCounterAheadOfItsEntriesBecomesOneLegacyEntryForTheDifference() {
		Issue issue = issueWith("HIN-1", 120);
		tracked(issue, 30);

		WorkItemSchemaMigration.Result result = migration.migrate();

		assertThat(result.legacyEntriesCreated()).isEqualTo(1);
		assertThat(result.legacyMinutes()).isEqualTo(90);
		assertThat(legacyEntries()).singleElement().satisfies(entry -> {
			assertThat(entry.getDurationMinutes()).isEqualTo(90);
			assertThat(entry.getIssueId()).isEqualTo(issue.getId());
			assertThat(entry.getProjectId()).isEqualTo("p1");
			assertThat(entry.getUserId()).as("never attributed to a person").isNull();
			assertThat(entry.getDescription()).isEmpty();
			assertThat(entry.isBillable()).isFalse();
			assertThat(entry.getTags()).isEmpty();
		});
	}

	/**
	 * The idempotence that lets this run on every boot: after the first pass the
	 * remainder is zero, and a remainder is the only thing that creates an entry.
	 */
	@Test
	void aSecondRunChangesNothing() {
		Issue issue = issueWith("HIN-1", 120);
		tracked(issue, 30);
		migration.migrate();
		List<String> after = workItems.findAll().stream().map(WorkItem::getId).sorted().toList();

		WorkItemSchemaMigration.Result second = migration.migrate();

		assertThat(second.legacyEntriesCreated()).isZero();
		assertThat(second.defaultsBackfilled()).isZero();
		assertThat(workItems.findAll().stream().map(WorkItem::getId).sorted().toList())
				.isEqualTo(after);
	}

	/**
	 * The second run is cheap as well as harmless: with the marker in place it
	 * does not touch work_items or issues at all, which is what keeps a deploy
	 * from re-summing the whole tracked corpus every time.
	 */
	@Test
	void aSecondRunDoesNotEvenLook() {
		Issue issue = issueWith("HIN-1", 120);
		tracked(issue, 30);
		migration.migrate();

		assertThat(mongo.getCollection(WorkItemSchemaMigration.MARKERS)
				.countDocuments(new Document("_id", WorkItemSchemaMigration.MARKER)))
				.as("the run left a marker behind").isEqualTo(1);

		// Something the second run would fix if it looked: a document with no
		// source. It stays untouched, which is how we know it did not.
		mongo.getCollection("work_items").insertOne(new Document("issueId", issue.getId())
				.append("userId", "someone").append("durationMinutes", 5));

		assertThat(migration.migrate().defaultsBackfilled()).isZero();
		assertThat(mongo.getCollection("work_items")
				.countDocuments(new Document("source", null))).isEqualTo(1);
	}

	/**
	 * Two instances booting together both see the same remainder, and the marker
	 * — written at the end — protects neither. The id does: the second insert is
	 * a duplicate key, not a second entry, so the issue's hours cannot double.
	 */
	@Test
	void twoRunsAtOnceCannotDoubleTheHours() {
		Issue issue = issueWith("HIN-1", 480);

		migration.migrate();
		// Exactly what a racing instance does: it never saw the marker.
		mongo.getCollection(WorkItemSchemaMigration.MARKERS).deleteMany(new Document());
		WorkItemSchemaMigration.Result racing = migration.migrate();

		assertThat(racing.legacyEntriesCreated()).isZero();
		assertThat(workItems.findAll()).singleElement()
				.satisfies(entry -> assertThat(entry.getDurationMinutes()).isEqualTo(480));
	}

	/**
	 * A counter grown over years of #time bumps can hold far more than a day,
	 * and a day is the most any entry may hold — one lump of two hundred hours
	 * would be a record the API itself refuses to edit.
	 */
	@Test
	void aRemainderLargerThanADayBecomesSeveralEntriesOfAtMostADay() {
		issueWith("HIN-1", 3 * 24 * 60 + 30);

		WorkItemSchemaMigration.Result result = migration.migrate();

		assertThat(result.legacyEntriesCreated()).isEqualTo(4);
		assertThat(result.legacyMinutes()).isEqualTo(3 * 24 * 60 + 30);
		assertThat(workItems.findAll()).hasSize(4)
				.allSatisfy(entry -> {
					assertThat(entry.getDurationMinutes())
							.isBetween(1, TimeTrackingService.MAX_MINUTES);
					assertThat(entry.getSource()).isEqualTo(WorkItem.Source.LEGACY);
					assertThat(entry.getUserId()).isNull();
				});
		assertThat(workItems.findAll().stream()
				.mapToInt(WorkItem::getDurationMinutes).sum()).isEqualTo(3 * 24 * 60 + 30);
	}

	@Test
	void anIssueWhoseEntriesAlreadyAddUpGetsNothing() {
		Issue issue = issueWith("HIN-1", 60);
		tracked(issue, 25);
		tracked(issue, 35);

		assertThat(migration.migrate().legacyEntriesCreated()).isZero();
		assertThat(legacyEntries()).isEmpty();
	}

	@Test
	void anIssueWithNoCounterAtAllIsNotTouched() {
		issueWith("HIN-1", 0);

		assertThat(migration.migrate().legacyEntriesCreated()).isZero();
		assertThat(workItems.count()).isZero();
	}

	/** A counter that lags its entries is not a remainder — nothing is invented and nothing is removed. */
	@Test
	void aCounterBehindItsEntriesCreatesNothing() {
		Issue issue = issueWith("HIN-1", 10);
		tracked(issue, 45);

		assertThat(migration.migrate().legacyEntriesCreated()).isZero();
		assertThat(workItems.count()).isEqualTo(1);
	}

	/** Every issue in a batch is settled on its own, not against the batch total. */
	@Test
	void eachIssueIsSettledSeparately() {
		Issue first = issueWith("HIN-1", 120);
		tracked(first, 30);
		Issue second = issueWith("HIN-2", 45);
		Issue third = issueWith("HIN-3", 60);
		tracked(third, 60);

		WorkItemSchemaMigration.Result result = migration.migrate();

		assertThat(result.legacyEntriesCreated()).isEqualTo(2);
		assertThat(result.legacyMinutes()).isEqualTo(90 + 45);
		assertThat(legacyEntries()).extracting(WorkItem::getIssueId)
				.containsExactlyInAnyOrder(first.getId(), second.getId());
	}

	/** More issues than one cursor batch: the walk must not stop at the batch boundary. */
	@Test
	void theWalkCoversMoreIssuesThanOneBatch() {
		int count = WorkItemSchemaMigration.BATCH + 7;
		for (int i = 0; i < count; i++) {
			issueWith("HIN-" + i, 15);
		}

		assertThat(migration.migrate().legacyEntriesCreated()).isEqualTo(count);
		assertThat(legacyEntries()).hasSize(count);
	}

	// --- the field defaults ----------------------------------------------------------

	/**
	 * A document written before 2.0 has no {@code source}, {@code billable} or
	 * {@code tags} at all. Readers tolerate that, but an equality filter on
	 * {@code billable == false} cannot match a field that is not there.
	 */
	@Test
	void entriesWrittenBeforeTwoPointZeroGetTheDocumentedDefaults() {
		Issue issue = issueWith("HIN-1", 30);
		mongo.getCollection("work_items").insertOne(new Document("issueId", issue.getId())
				.append("projectId", "p1").append("userId", "u1")
				.append("date", new Date()).append("durationMinutes", 30)
				.append("activityType", "Development"));

		assertThat(migration.migrate().defaultsBackfilled()).isEqualTo(3);

		Document stored = mongo.getCollection("work_items").find().first();
		assertThat(stored).isNotNull();
		assertThat(stored.getString("source")).isEqualTo("APP");
		assertThat(stored.getBoolean("billable")).isFalse();
		assertThat(stored.getList("tags", String.class)).isEmpty();
	}

	/** The defaults pass must not disturb an entry that already declares them. */
	@Test
	void anEntryThatAlreadyCarriesTheFieldsIsLeftAlone() {
		Issue issue = issueWith("HIN-1", 30);
		WorkItem entry = workItems.save(WorkItem.builder().issueId(issue.getId()).projectId("p1")
				.userId("u1").date(java.time.LocalDate.now()).durationMinutes(30)
				.source(WorkItem.Source.MCP).billable(true)
				.tags(new ArrayList<>(List.of("kept"))).build());

		assertThat(migration.migrate().defaultsBackfilled()).isZero();

		WorkItem after = workItems.findById(entry.getId()).orElseThrow();
		assertThat(after.getSource()).isEqualTo(WorkItem.Source.MCP);
		assertThat(after.isBillable()).isTrue();
		assertThat(after.getTags()).containsExactly("kept");
	}
}
