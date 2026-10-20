package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.TestMongo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance without Mongo transactions, whose operator nevertheless switched shift planning on
 * through the environment (HIN-49).
 *
 * <p>The module stays off: assigning the last place on a shift is a transaction, and running it
 * without one would hand that place to two people. {@code /meta} says why the admin area cannot
 * offer the switch ({@code shiftPlanningAvailable: false}), and the log says it at start.
 *
 * <p>The output capture is registered before the context starts, so the start-up warning is part
 * of what it sees.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=false",
		"hinata.shifts.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ShiftPlanningWithoutTransactionsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private ShiftSettings shiftSettings;

	@Test
	@DisplayName("the flag stays off, /meta reports the module unavailable, and the log says why")
	void withoutTransactionsTheModuleStaysOff(CapturedOutput output) throws Exception {
		assertThat(shiftSettings.configured()).isTrue();
		assertThat(shiftSettings.available()).isFalse();
		assertThat(shiftSettings.enabled()).isFalse();

		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/meta")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		JsonNode meta = JSON.readTree(response.body());

		assertThat(meta.path("featureFlags").path("shift_planning").asBoolean(true)).isFalse();
		assertThat(meta.path("shiftPlanningAvailable").isBoolean()).isTrue();
		assertThat(meta.path("shiftPlanningAvailable").asBoolean(true)).isFalse();

		assertThat(output.getAll())
				.contains("Shift planning stays off although it is switched on")
				.contains("hinata.mongodb.transactions-enabled");
	}
}
