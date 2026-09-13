package com.ahmadre.hinata.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ahmadre.hinata.richtext.RichTextService;
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

import java.util.Date;
import java.util.List;

/**
 * Articles written while the key pattern allowed letters only carry a backlink
 * list without their links to projects like {@code EP26}. The re-derivation has
 * to restore those — and nothing else — against a real MongoDB, because what it
 * must never do (clear a list, rewrite a row that was right) is a persistence
 * behaviour.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ArticleIssueKeysBackfillTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;

	@Autowired
	private ArticleIssueKeysBackfill backfill;

	@Autowired
	private RichTextService richText;

	@BeforeEach
	void clean() {
		// Clearing the marker puts each test back in the position of a server that
		// has not run this yet — the context boot already ran it on an empty database.
		mongo.getCollection("articles").deleteMany(new Document());
		mongo.getCollection("migrations").deleteMany(new Document());
	}

	/** An article as the old derivation stored it: the document, and the keys it kept. */
	private void article(String id, String markdown, List<String> storedKeys) {
		mongo.getCollection("articles").insertOne(new Document("_id", id)
				.append("title", id)
				.append("contentDoc", richText.fromMarkdown(markdown).doc())
				.append("referencedIssueKeys", storedKeys)
				.append("updatedAt", new Date(1_700_000_000_000L)));
	}

	@SuppressWarnings("unchecked")
	private List<String> keys(String id) {
		return (List<String>) mongo.getCollection("articles")
				.find(new Document("_id", id)).first().get("referencedIssueKeys");
	}

	/** How many rows the finished run reported writing. */
	private long updatedByRun() {
		Document marker = mongo.getCollection("migrations")
				.find(new Document("_id", ArticleIssueKeysBackfill.MARKER_ID)).first();
		assertThat(marker).as("a completed run leaves its marker").isNotNull();
		return marker.getLong("updated");
	}

	@Test
	void restoresTheLinksTheLettersOnlyPatternDropped() {
		article("a-ep26", "Siehe {{issue:EP26-2}} und {{issue:HIN-1}}", List.of("HIN-1"));

		backfill.run(null);

		assertThat(keys("a-ep26")).containsExactly("EP26-2", "HIN-1");
		assertThat(updatedByRun()).isEqualTo(1);
	}

	@Test
	void aRowThatIsAlreadyRightIsNotWritten() {
		article("a-kontakt", "[Mail](mailto:a@b.c) und [Webseite](https://example.org)", List.of());
		article("a-hin", "{{issue:HIN-1}}", List.of("HIN-1"));

		backfill.run(null);

		assertThat(keys("a-kontakt")).isEmpty();
		assertThat(keys("a-hin")).containsExactly("HIN-1");
		assertThat(updatedByRun()).isZero();
	}

	@Test
	void anUnreadableDocumentKeepsWhatItHasAndDoesNotStopTheRun() {
		mongo.getCollection("articles").insertOne(new Document("_id", "a-broken")
				.append("contentDoc", "{not json").append("referencedIssueKeys", List.of("HIN-9")));
		article("a-ep26", "{{issue:EP26-2}}", List.of());

		assertThatCode(() -> backfill.run(null)).doesNotThrowAnyException();

		assertThat(keys("a-broken")).containsExactly("HIN-9");
		assertThat(keys("a-ep26")).containsExactly("EP26-2");
	}

	@Test
	void runsOnceAndMarksItselfDone() {
		article("a-ep26", "{{issue:EP26-2}}", List.of());

		backfill.run(null);
		assertThat(updatedByRun()).isEqualTo(1);

		// A later row with a stale list is not touched: the marker ends the scan.
		article("a-later", "{{issue:EP26-3}}", List.of());
		backfill.run(null);
		assertThat(keys("a-later")).isEmpty();
	}
}
