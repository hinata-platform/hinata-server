package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client sees while absence management is off, and what it takes to turn it on.
 *
 * <p>Two switches, and the interesting cases are the ones in between. The module has its own
 * switch — an administrator turns it on deliberately, because holiday principles and the holiday
 * plan are subject to co-determination (§ 87 Abs. 1 Nr. 5 BetrVG) — but it is built on working
 * patterns, holiday calendars and capacity, which belong to the extended time-tracking module. So
 * "absence management on, extended module off" must answer exactly like "off": the routes are not
 * there, and {@code /meta} says so, or the app would offer buttons that 404.
 *
 * <p>Over real HTTP for the same reason as its sibling for the extended module: the layering is
 * the subject. The request passes the security chain first, the interceptor runs although no
 * controller is mapped under the prefix yet, and the refusal comes back as the ordinary localized
 * error body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// The shipped defaults, stated rather than assumed: this file is about the
		// instance nobody has opted in on.
		"hinata.time-tracking.advanced-enabled=false",
		"hinata.time-tracking.absence-management-enabled=false"
})
@Import(AbsenceManagementGateIntegrationTest.GatedProbe.class)
@Testcontainers(disabledWithoutDocker = true)
class AbsenceManagementGateIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";
	private static final String FLAG = "absence_management";

	/** A handler behind the gated prefix, so "the gate opened" reads as a 200 rather than as a different 404. */
	@TestConfiguration
	static class GatedProbe {

		@RestController
		static class Probe {

			@GetMapping({ "/api/v1/time-off", "/api/v1/time-off/probe" })
			Map<String, String> probe() {
				return Map.of("reached", "yes");
			}
		}
	}

	/** Both forms of the prefix: {@code /a/**} does not match {@code /a}, and clients call both. */
	private static final List<String> GATED = List.of("/api/v1/time-off", "/api/v1/time-off/probe");

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private SettingsService settings;

	@BeforeAll
	static void dockerImagePinned() {
		assertThat(MONGO.getDockerImageName()).contains(TestMongo.IMAGE);
	}

	@AfterEach
	void clearOverride() {
		ServerSettings current = settings.get();
		current.setTimeTracking(null);
		settings.save(current);
	}

	// --- the flag off --------------------------------------------------------

	@Test
	@DisplayName("with nothing configured, /meta says absence management is off")
	void metaPublishesTheFlagAsOff() {
		JsonNode flags = body(get("/api/v1/meta", null)).path("featureFlags");

		assertThat(flags.path(FLAG).asBoolean(true)).isFalse();
		// The flags that were already there keep their own values; a new module flag
		// must not disturb the map the published app reads.
		assertThat(flags.has("advanced_time_tracking")).isTrue();
		assertThat(flags.has("mcp")).isTrue();
	}

	@Test
	@DisplayName("the module's prefix answers 404 error.feature.disabled")
	void theGatedPrefixIsNotFoundWhileTheModuleIsOff() {
		String token = login();

		for (String path : GATED) {
			HttpResponse<String> response = get(path, token, "en");
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			// The localized body of error.feature.disabled, so a client can tell this apart
			// from a genuine not-found and re-read /meta rather than show an error page.
			assertThat(body(response).path("message").asText()).as(path)
					.isEqualTo("This feature is not enabled on this server");
		}
	}

	@Test
	@DisplayName("the refusal is localized like every other error")
	void theRefusalSpeaksTheRequestLanguage() {
		HttpResponse<String> response = get("/api/v1/time-off/probe", login(), "de");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(body(response).path("message").asText())
				.isEqualTo("Diese Funktion ist auf diesem Server nicht aktiviert");
	}

	// --- the two switches ----------------------------------------------------

	@Test
	@DisplayName("the extended module alone does not bring absence management with it")
	void extendedModuleOnIsNotEnough() {
		switches(true, null);
		String token = login();

		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(true))
				.isFalse();
		for (String path : GATED) {
			assertThat(get(path, token).statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("absence management alone, over an extended module that is off, stays off")
	void absenceManagementWithoutItsFoundationStaysOff() {
		switches(null, true);
		String token = login();

		// The switch is stored as somebody left it — the admin screen shows that position and
		// explains why it cannot take effect — but nothing in the product acts on it.
		assertThat(settings.get().getTimeTracking().getAbsenceManagementEnabled()).isTrue();
		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(true))
				.isFalse();
		for (String path : GATED) {
			assertThat(get(path, token).statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("both switches on: the routes exist, and off again closes them without a restart")
	void bothSwitchesOnOpensTheModule() {
		switches(true, true);
		String token = login();

		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(false))
				.isTrue();
		for (String path : GATED) {
			HttpResponse<String> response = get(path, token);
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			assertThat(body(response).path("reached").asText()).as(path).isEqualTo("yes");
		}

		switches(true, false);

		for (String path : GATED) {
			assertThat(get(path, token).statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("an anonymous caller is refused before the gate is reached")
	void authenticationComesFirst() {
		switches(true, true);

		assertThat(get("/api/v1/time-off/probe", null).statusCode()).isEqualTo(401);
	}

	// --- helpers -------------------------------------------------------------

	/** Stores the two switches; null leaves a field at "no opinion", which is what a fresh instance has. */
	private void switches(Boolean advanced, Boolean absenceManagement) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(advanced);
		block.setAbsenceManagementEnabled(absenceManagement);
		current.setTimeTracking(block);
		// save() publishes SettingsChangedEvent — the same path the admin PUT takes, and the only
		// thing between "an admin flipped a switch" and "the next request is answered differently".
		settings.save(current);
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> get(String path, String token) {
		return get(path, token, null);
	}

	private HttpResponse<String> get(String path, String token, String language) {
		HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (language != null) {
			request.header("Accept-Language", language);
		}
		return send(request.build());
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + request.uri(), ex);
		}
	}

	private JsonNode body(HttpResponse<String> response) {
		try {
			return JSON.readTree(response.body());
		}
		catch (Exception ex) {
			throw new IllegalStateException("not JSON: " + response.body(), ex);
		}
	}

	private String login() {
		HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/v1/auth/login"))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"identifier\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
				.build());
		assertThat(response.statusCode()).as("login").isEqualTo(200);
		return body(response).path("accessToken").asText();
	}
}
