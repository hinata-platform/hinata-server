package com.ahmadre.hinata.mailingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Which rows this runner selects is the whole of its safety, so it is exercised
 * against a real MongoDB: the defect it must not have — quietly rewriting a
 * field on every boot — is a query behaviour, not a Java behaviour.
 *
 * <p>Since the Lexical migration, {@code description} is no longer the stored
 * content. It is the plain text derived from {@code descriptionDoc}, and writing
 * markdown into it puts the two permanently out of step: the editor shows one
 * thing, search and notification teasers say another, and the Lexical backfill
 * never repairs it because its own filter only matches rows with no document.
 * This runner is ordered ahead of that backfill so it cleans HTML <em>before</em>
 * conversion; after conversion there is nothing here left to do.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestedHtmlDescriptionBackfillMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String HEADER = "Created from e-mail by a@b.de\n\n---\n\n";
	private static final String HTML_BODY = "<table>\n<tr><td>x</td></tr>\n</table>";

	private MongoTemplate mongo;
	private IngestedHtmlDescriptionBackfill backfill;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getConnectionString()), "ingest-test");
		mongo.getCollection("issues").deleteMany(new Document());
		backfill = new IngestedHtmlDescriptionBackfill(mongo);
	}

	private void insert(Document row) {
		mongo.getCollection("issues").insertOne(row);
	}

	private String descriptionOf(String id) {
		return mongo.getCollection("issues").find(new Document("_id", id)).first()
				.getString("description");
	}

	@Test
	void stillCleansAnIngestedRowThatHasNotBeenConvertedYet() {
		insert(new Document("_id", "i1")
				.append("ingestConnectionId", "conn-1")
				.append("description", HEADER + HTML_BODY));

		backfill.run(null);

		assertThat(descriptionOf("i1")).doesNotContain("<table>").startsWith(HEADER);
	}

	@Test
	void leavesAConvertedRowAloneEvenWhenItsDerivedTextLooksLikeHtml() {
		// The converter keeps raw HTML blocks as literal text, so the *derived*
		// description of an ingested mail legitimately contains "<table>". Matching
		// on that and rewriting it is how this runner used to break the derived-text
		// invariant on every boot after the first.
		String derived = HEADER + HTML_BODY;
		insert(new Document("_id", "i1")
				.append("ingestConnectionId", "conn-1")
				.append("description", derived)
				.append("descriptionDoc", "{\"root\":{\"type\":\"root\",\"children\":[]}}"));

		backfill.run(null);

		assertThat(descriptionOf("i1"))
				.as("a converted row's derived text must never be rewritten")
				.isEqualTo(derived);
	}

	@Test
	void neverTouchesAUserAuthoredIssue() {
		insert(new Document("_id", "i2").append("description", HEADER + HTML_BODY));

		backfill.run(null);

		assertThat(descriptionOf("i2")).isEqualTo(HEADER + HTML_BODY);
	}
}
