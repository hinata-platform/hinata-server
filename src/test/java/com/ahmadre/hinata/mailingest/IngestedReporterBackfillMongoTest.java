package com.ahmadre.hinata.mailingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

/**
 * Which rows this runner selects is the whole of its safety — it writes an author onto
 * existing tickets — so it is exercised against a real MongoDB: picking the wrong rows is
 * a query behaviour, not a Java behaviour.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestedReporterBackfillMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private MongoTemplate mongo;
	private UserService users;
	private IngestedReporterBackfill backfill;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getConnectionString()), "reporter-test");
		mongo.getCollection("issues").deleteMany(new Document());
		users = mock(UserService.class);
		lenient().when(users.findActiveByEmail(anyString())).thenReturn(Optional.empty());
		backfill = new IngestedReporterBackfill(mongo, users);
	}

	private void insert(Document row) {
		mongo.getCollection("issues").insertOne(row);
	}

	private String reporterOf(String id) {
		return mongo.getCollection("issues").find(new Document("_id", id)).first()
				.getString("reporterId");
	}

	@Test
	void attributesAnIngestedTicketToItsSendersAccount() {
		when(users.findActiveByEmail("a@b.de"))
				.thenReturn(Optional.of(User.builder().id("u-1").build()));
		insert(new Document("_id", "i1")
				.append("ingestConnectionId", "conn-1")
				.append("reporterEmail", "a@b.de"));

		backfill.run(null);

		assertThat(reporterOf("i1")).isEqualTo("u-1");
	}

	@Test
	void leavesATicketWhoseSenderResolvesToNobodyAlone() {
		insert(new Document("_id", "i1")
				.append("ingestConnectionId", "conn-1")
				.append("reporterEmail", "stranger@b.de"));

		backfill.run(null);

		assertThat(reporterOf("i1")).isNull();
	}

	@Test
	void neverOverwritesAnAuthorThatIsAlreadySet() {
		when(users.findActiveByEmail(anyString()))
				.thenReturn(Optional.of(User.builder().id("u-other").build()));
		insert(new Document("_id", "i1")
				.append("ingestConnectionId", "conn-1")
				.append("reporterEmail", "a@b.de")
				.append("reporterId", "u-1"));

		backfill.run(null);

		assertThat(reporterOf("i1")).isEqualTo("u-1");
	}

	@Test
	void neverTouchesAUserAuthoredIssue() {
		when(users.findActiveByEmail(anyString()))
				.thenReturn(Optional.of(User.builder().id("u-1").build()));
		insert(new Document("_id", "i2").append("title", "Hand-written"));

		backfill.run(null);

		assertThat(reporterOf("i2")).isNull();
	}
}
