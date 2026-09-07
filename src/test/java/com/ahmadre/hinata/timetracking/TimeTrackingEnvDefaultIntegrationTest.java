package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance that has been running since before this block existed has a
 * {@code server_settings} document with no {@code timeTracking} field in it. The
 * failure this guards against is quiet and total: read that absence as "all
 * false" and an operator who switched the module on in their environment finds
 * it off after an upgrade, with nothing in the settings to explain why.
 *
 * <p>Against a real Mongo document rather than a constructed object, because the
 * absence is a property of what is stored — a Java default would hide it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// An operator who turned the module on in their environment, and a couple
		// of policies alongside it, so "the default won" is distinguishable from
		// "everything happened to be false anyway".
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.currency=CHF",
		"hinata.time-tracking.approval-period.type=WEEKLY",
		"hinata.time-tracking.approval-period.week-starts-on=SUNDAY"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeTrackingEnvDefaultIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TimeTrackingSettings policy;

	@BeforeEach
	void settingsDocumentWithoutTheBlock() {
		mongo.getCollection("server_settings").deleteMany(new Document());
		// Saved through the service so the refresh event fires exactly as it does
		// in production; the block is absent because nothing ever wrote it.
		settings.save(new ServerSettings());
		assertThat(mongo.getCollection("server_settings")
				.find(new Document("_id", ServerSettings.SINGLETON_ID)).first())
				.satisfies(stored -> assertThat(stored.containsKey("timeTracking")).isFalse());
	}

	@Test
	@DisplayName("the missing block falls back to the environment, not to false")
	void theResolverReadsAnAbsentBlockAsTheEnvironmentDefault() {
		assertThat(policy.advancedEnabled()).isTrue();
		assertThat(policy.currency()).isEqualTo("CHF");
		assertThat(policy.approvalPeriod()).isEqualTo(new TimeTrackingSettings.ApprovalPeriod(
				TimePolicy.ApprovalPeriod.WEEKLY, DayOfWeek.SUNDAY, null, null));
	}

	@Test
	@DisplayName("/meta publishes the environment default as the flag")
	void metaPublishesTheEnvironmentDefault() {
		JsonNode meta = get("/api/v1/meta");

		assertThat(meta.path("featureFlags").path("advanced_time_tracking").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("an override of one policy leaves the other defaults standing")
	void oneOverrideDoesNotFreezeTheRest() {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setBillingEnabled(true);
		current.setTimeTracking(block);
		settings.save(current);

		assertThat(policy.billingEnabled()).isTrue();
		assertThat(policy.advancedEnabled()).isTrue();
		assertThat(policy.currency()).isEqualTo("CHF");
		assertThat(get("/api/v1/meta").path("featureFlags")
				.path("advanced_time_tracking").asBoolean()).isTrue();
	}

	private JsonNode get(String path) {
		try {
			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			return JSON.readTree(response.body());
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + path, ex);
		}
	}
}
