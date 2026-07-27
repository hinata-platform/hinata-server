package com.ahmadre.hinata.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ahmadre.hinata.richtext.RichTextService;
import com.mongodb.client.model.IndexOptions;
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

import java.util.List;

/**
 * The backfill is the one piece of this change that rewrites data users already
 * have, so it is exercised against a real MongoDB rather than a mock: the
 * failure it must not have — a row silently left half-converted — is a
 * persistence behaviour, not a Java behaviour.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		// No demo data: this test writes the exact legacy rows it wants to see
		// migrated, and a seeded workspace would drown them.
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class MarkdownToLexicalBackfillTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;

	@Autowired
	private MarkdownToLexicalBackfill backfill;

	@Autowired
	private RichTextService richText;

	@BeforeEach
	void clean() {
		// `migrations` holds the completion marker. Clearing it is what puts each
		// test back in the position of a server that has not run this yet — the
		// context boot already ran the migration once against an empty database.
		for (String collection : List.of("issues", "articles", "issue_comments", "migrations")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
	}

	private Document marker() {
		return mongo.getCollection("migrations").find(new Document("_id", "markdown-to-lexical"))
				.first();
	}

	/** A row as it looked before this change: markdown in the text field, no document. */
	private void legacy(String collection, String id, String field, String markdown) {
		insert(collection, new Document("_id", id).append(field, markdown));
	}

	/**
	 * Inserts a raw row, giving issues a distinct project/number pair. The
	 * collection carries a unique index on that pair, so rows that leave it unset
	 * collide with each other rather than with anything this test is about.
	 */
	private void insert(String collection, Document row) {
		if ("issues".equals(collection)) {
			row.append("projectId", "p-" + row.get("_id")).append("numberInProject", 1L);
		}
		mongo.getCollection(collection).insertOne(row);
	}

	private Document read(String collection, String id) {
		return mongo.getCollection(collection).find(new Document("_id", id)).first();
	}

	@Test
	void convertsLegacyMarkdownInEveryContentCollection() {
		legacy("issues", "i1", "description", "## Ziel\n\nEin **wichtiger** Punkt.");
		legacy("articles", "a1", "content", "Siehe {{issue:HIN-5}} dazu.");
		legacy("issue_comments", "c1", "text", "sieht `gut` aus");

		backfill.run(null);

		Document issue = read("issues", "i1");
		assertThat(issue.getString("descriptionDoc")).contains("\"heading\"").contains("\"tag\":\"h2\"");
		assertThat(issue.getString("description")).isEqualTo("Ziel\nEin wichtiger Punkt.");

		Document article = read("articles", "a1");
		assertThat(article.getString("contentDoc")).contains("\"smartlink\"");
		assertThat(article.getString("content")).isEqualTo("Siehe HIN-5 dazu.");

		Document comment = read("issue_comments", "c1");
		assertThat(comment.getString("textDoc")).contains("\"text\":\"gut\"");
		assertThat(comment.getString("text")).isEqualTo("sieht gut aus");
	}

	@Test
	void derivesArticleBacklinksSoTheyWorkImmediatelyAfterUpgrade() {
		// Without this an upgraded knowledge base would answer "no articles
		// reference this issue" until every article happened to be edited again.
		legacy("articles", "a1", "content", "Owner {{user:u1}} — driven by {{issue:hin-2}}.");

		backfill.run(null);

		assertThat(read("articles", "a1").getList("referencedIssueKeys", String.class))
				.containsExactly("HIN-2");
	}

	@Test
	void isIdempotentAndLeavesASecondRunAlone() {
		legacy("issues", "i1", "description", "unverändert");

		backfill.run(null);
		String afterFirst = read("issues", "i1").getString("descriptionDoc");
		backfill.run(null);

		assertThat(read("issues", "i1").getString("descriptionDoc")).isEqualTo(afterFirst);
	}

	@Test
	void doesNotTouchARowThatAlreadyHasADocument() {
		// An issue written by the new app already holds a document; re-deriving its
		// plain text from a stale markdown field would overwrite the newer content.
		insert("issues", new Document("_id", "i1")
				.append("description", "alter markdown")
				.append("descriptionDoc", richText.fromMarkdown("neuer inhalt").doc()));

		backfill.run(null);

		Document issue = read("issues", "i1");
		assertThat(issue.getString("description")).isEqualTo("alter markdown");
		assertThat(issue.getString("descriptionDoc")).contains("neuer inhalt");
	}

	@Test
	void skipsRowsWithNothingToConvert() {
		insert("issues", new Document("_id", "empty"));
		insert("issues", new Document("_id", "blank").append("description", ""));

		backfill.run(null);

		assertThat(read("issues", "empty").get("descriptionDoc")).isNull();
		assertThat(read("issues", "blank").get("descriptionDoc")).isNull();
	}

	@Test
	void convertsMoreRowsThanOneBatch() {
		// The migration streams in batches; a corpus larger than one batch is the
		// case where an off-by-one would leave a tail unconverted.
		for (int i = 0; i < 450; i++) {
			legacy("issue_comments", "c" + i, "text", "Kommentar **" + i + "**");
		}

		backfill.run(null);

		long converted = mongo.getCollection("issue_comments")
				.countDocuments(new Document("textDoc", new Document("$exists", true)));
		assertThat(converted).isEqualTo(450);
		assertThat(read("issue_comments", "c449").getString("text")).isEqualTo("Kommentar 449");
	}

	/**
	 * The conversion is a one-way, lossy projection over the only copy of the
	 * content. Keeping the pre-migration markdown next to it is what makes a
	 * converter defect a re-run rather than permanent data loss — the failure this
	 * whole migration must not have.
	 */
	@Test
	void keepsTheOriginalMarkdownInAShadowFieldSoTheRunIsRecoverable() {
		String issueMd = "Wir brauchen List<String>, siehe **hier**.";
		String articleMd = "# Titel\n\nSiehe {{issue:HIN-5}}.";
		String commentMd = "sieht `gut` aus";
		legacy("issues", "i1", "description", issueMd);
		legacy("articles", "a1", "content", articleMd);
		legacy("issue_comments", "c1", "text", commentMd);

		backfill.run(null);

		assertThat(read("issues", "i1").getString("descriptionMd")).isEqualTo(issueMd);
		assertThat(read("articles", "a1").getString("contentMd")).isEqualTo(articleMd);
		assertThat(read("issue_comments", "c1").getString("textMd")).isEqualTo(commentMd);
		// And the field it protects really was overwritten with the projection.
		assertThat(read("issues", "i1").getString("description")).doesNotContain("**");
	}

	/**
	 * A migration whose write fails must not take the application down with it.
	 * {@code flush} is the only I/O in the runner, and an {@code ApplicationRunner}
	 * that throws makes Spring close the context — the server does not start, and
	 * does not start on the next boot either when the cause is a row.
	 */
	@Test
	void aBatchThatCannotBeWrittenDoesNotAbortTheRunOrTheApplication() {
		legacy("issue_comments", "c1", "text", "identisch");
		legacy("issue_comments", "c2", "text", "identisch");
		// Both rows convert to byte-identical documents, so a unique index on the
		// document field makes the second write of the batch fail for real.
		mongo.getCollection("issue_comments").createIndex(new Document("textDoc", 1),
				new IndexOptions().unique(true).name("test_unique_text_doc")
						.partialFilterExpression(
								new Document("textDoc", new Document("$exists", true))));
		try {
			assertThatCode(() -> backfill.run(null)).doesNotThrowAnyException();

			// The batch is ordered, so the first row landed and the second did not.
			assertThat(read("issue_comments", "c1").getString("textDoc")).isNotNull();
			assertThat(read("issue_comments", "c2").get("textDoc")).isNull();
			// And the run is not marked complete, so the next boot retries it.
			assertThat(marker()).isNull();
		}
		finally {
			mongo.getCollection("issue_comments").dropIndex("test_unique_text_doc");
		}
	}

	// --- completion marker ----------------------------------------------------

	/**
	 * The filter has no supporting index, so without a marker every boot pays a
	 * full scan of three collections before the server is ready — forever, on an
	 * installation where the answer has been "nothing to do" for a year.
	 */
	@Test
	void aCompletedRunIsRecordedAndNotRescanned() {
		legacy("issues", "i1", "description", "erste");

		backfill.run(null);

		assertThat(marker()).isNotNull();
		assertThat(marker().get("completedAt")).isNotNull();

		// A row that appears after completion is not scanned for: the marker is the
		// statement that this migration is done, not a cache of the last cursor.
		legacy("issues", "i2", "description", "zweite");
		backfill.run(null);
		assertThat(read("issues", "i2").get("descriptionDoc")).isNull();
	}

	@Test
	void runsAgainWhenTheMarkerIsAbsent() {
		legacy("issues", "i1", "description", "erste");
		backfill.run(null);
		assertThat(read("issues", "i1").getString("descriptionDoc")).isNotNull();

		// Operator drops the marker to force a re-run; a row without a document is
		// picked up again.
		mongo.getCollection("migrations").deleteMany(new Document());
		legacy("issues", "i2", "description", "zweite");
		backfill.run(null);

		assertThat(read("issues", "i2").getString("descriptionDoc")).isNotNull();
	}

	@Test
	void aRowThatCannotConvertIsLeftForARetryRatherThanAborting() {
		// One bad row must not stop the other ten thousand, and leaving it untouched
		// is what lets a later run pick it up.
		legacy("issues", "good1", "description", "erste");
		insert("issues", new Document("_id", "odd").append("description", 42));
		legacy("issues", "good2", "description", "zweite");

		backfill.run(null);

		assertThat(read("issues", "good1").getString("descriptionDoc")).isNotNull();
		assertThat(read("issues", "good2").getString("descriptionDoc")).isNotNull();
		assertThat(read("issues", "odd").get("descriptionDoc")).isNull();
	}
}
