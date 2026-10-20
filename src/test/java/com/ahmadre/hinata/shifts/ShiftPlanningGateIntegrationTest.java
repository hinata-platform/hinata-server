package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.Document;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
 * What a client sees while shift planning is off, and what it takes to turn it on (HIN-49).
 *
 * <p>Over real HTTP, because the layering is the subject: the security chain first, then the
 * interceptor, then the ordinary localized error body. The probe stands in for the module's
 * handlers (HIN-43 brings them) under sub-paths nobody will ever route, so "the gate opened"
 * reads as a 200 rather than a different 404. The bare forms get no probe, because they are the
 * collection routes HIN-43 will own; for those an open gate is any answer that is not the
 * module's refusal.
 *
 * <p>Transactions are on here, as on a replica set: the switch is the only thing deciding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		// The shipped default, stated rather than assumed.
		"hinata.shifts.enabled=false"
})
@Import(ShiftPlanningGateIntegrationTest.GatedProbe.class)
@Testcontainers(disabledWithoutDocker = true)
class ShiftPlanningGateIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";
	private static final String FLAG = "shift_planning";
	private static final String DISABLED_EN = "This feature is not enabled on this server";

	@TestConfiguration
	static class GatedProbe {

		@RestController
		static class Probe {

			@GetMapping({ "/api/v1/shifts/__probe", "/api/v1/me/shifts/__probe",
					"/api/v1/projects/PROBE/shifts/__probe" })
			Map<String, String> probe() {
				return Map.of("reached", "yes");
			}
		}
	}

	/** The three bare forms: {@code /a/**} does not match {@code /a}, so each is gated on its own. */
	private static final List<String> BARE = List.of(
			"/api/v1/shifts", "/api/v1/me/shifts", "/api/v1/projects/PROBE/shifts");

	/** One sub-path under each of the three prefixes. */
	private static final List<String> SUB_PATHS = List.of(
			"/api/v1/shifts/__probe", "/api/v1/me/shifts/__probe",
			"/api/v1/projects/PROBE/shifts/__probe");

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private SettingsService settings;

	@Autowired
	private ShiftSettings shiftSettings;

	@Autowired
	private MongoTemplate mongo;

	@BeforeAll
	static void dockerImagePinned() {
		assertThat(MONGO.getDockerImageName()).contains(TestMongo.IMAGE);
	}

	@AfterEach
	void clearOverride() {
		ServerSettings current = settings.get();
		current.setShifts(null);
		settings.save(current);
	}

	// --- the module off --------------------------------------------------------

	@Test
	@DisplayName("with nothing configured, /meta says shift planning is off but available")
	void metaPublishesTheFlagAsOff() {
		JsonNode meta = body(get("/api/v1/meta", null));

		assertThat(meta.path("featureFlags").path(FLAG).isBoolean()).isTrue();
		assertThat(meta.path("featureFlags").path(FLAG).asBoolean(true)).isFalse();
		assertThat(meta.path("shiftPlanningAvailable").asBoolean(false)).isTrue();
		// The other module flags keep their own values.
		assertThat(meta.path("featureFlags").has("advanced_time_tracking")).isTrue();
		assertThat(meta.path("featureFlags").has("project_templates")).isTrue();
	}

	@Test
	@DisplayName("every gate pattern answers 404 error.feature.disabled, bare form and sub-path")
	void everyPatternIsNotFoundWhileTheModuleIsOff() {
		String token = login();

		for (String path : concat(BARE, SUB_PATHS)) {
			HttpResponse<String> response = get(path, token, "en");
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			assertThat(body(response).path("message").asText()).as(path).isEqualTo(DISABLED_EN);
		}
		// Not only GET: a write to the collection is refused the same way.
		HttpResponse<String> post = send(HttpRequest.newBuilder(url("/api/v1/projects/PROBE/shifts"))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build());
		assertThat(post.statusCode()).isEqualTo(404);
		assertThat(body(post).path("message").asText()).isEqualTo(DISABLED_EN);
	}

	@Test
	@DisplayName("the refusal is localized like every other error")
	void theRefusalSpeaksTheRequestLanguage() {
		HttpResponse<String> response = get("/api/v1/me/shifts", login(), "de");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(body(response).path("message").asText())
				.isEqualTo("Diese Funktion ist auf diesem Server nicht aktiviert");
	}

	@Test
	@DisplayName("an anonymous caller is refused before the gate is reached")
	void authenticationComesFirst() {
		assertThat(get("/api/v1/shifts", null).statusCode()).isEqualTo(401);
	}

	// --- the switch ------------------------------------------------------------

	@Test
	@DisplayName("an administrator turns it on through the settings PUT, no restart, and off again")
	void theAdminSwitchOpensAndClosesTheModule() {
		String token = login();
		JsonNode fetched = body(get("/api/v1/admin/settings", token));
		// The block is there to write into, empty, with the position it resolves to beside it.
		assertThat(fetched.path("shifts").path("enabled").isNull()).isTrue();
		assertThat(fetched.path("shifts").path("effective").path("enabled").asBoolean(true)).isFalse();

		enabledThroughAdmin(true);

		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(false))
				.isTrue();
		for (String path : SUB_PATHS) {
			HttpResponse<String> response = get(path, token);
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			assertThat(body(response).path("reached").asText()).as(path).isEqualTo("yes");
		}
		for (String path : BARE) {
			assertThat(get(path, token).body()).as(path).doesNotContain(DISABLED_EN);
		}
		JsonNode after = body(get("/api/v1/admin/settings", token));
		assertThat(after.path("shifts").path("enabled").asBoolean(false)).isTrue();
		assertThat(after.path("shifts").path("effective").path("enabled").asBoolean(false)).isTrue();
		// The read-only view is never what got written.
		assertThat(settings.get().getShifts().getEffective()).isNull();

		enabledThroughAdmin(false);

		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(true))
				.isFalse();
		for (String path : concat(BARE, SUB_PATHS)) {
			assertThat(get(path, token).statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("clearing the switch hands it back to the environment; omitting the block keeps it")
	void clearedAndOmittedAreDifferentAnswers() {
		String token = login();
		enabledThroughAdmin(true);

		// A client that does not model the block saves something else: no opinion, nothing lost.
		ObjectNode withoutBlock = (ObjectNode) body(get("/api/v1/admin/settings", token));
		withoutBlock.remove("shifts");
		assertThat(putJson("/api/v1/admin/settings", withoutBlock.toString(), token).statusCode())
				.isEqualTo(200);
		assertThat(settings.get().getShifts().getEnabled()).isTrue();
		assertThat(shiftSettings.enabled()).isTrue();

		// "Use the environment default": the field is cleared and the environment (off) decides.
		ObjectNode cleared = (ObjectNode) body(get("/api/v1/admin/settings", token));
		((ObjectNode) cleared.path("shifts")).putNull("enabled");
		assertThat(putJson("/api/v1/admin/settings", cleared.toString(), token).statusCode())
				.isEqualTo(200);
		assertThat(settings.get().getShifts().getEnabled()).isNull();
		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(true))
				.isFalse();
	}

	@Test
	@DisplayName("an old settings document without the shifts field falls back to the environment")
	void anOldDocumentWithoutTheBlockFallsBackToTheEnvironment() {
		enabledThroughAdmin(true);
		assertThat(shiftSettings.enabled()).isTrue();

		// The document as a server before HIN-49 wrote it: no shifts field at all.
		Query singleton = Query.query(Criteria.where("_id").is(ServerSettings.SINGLETON_ID));
		mongo.updateFirst(singleton, new Update().unset("shifts"), "server_settings");
		Document raw = mongo.findOne(singleton, Document.class, "server_settings");
		assertThat(raw).isNotNull();
		assertThat(raw.containsKey("shifts")).isFalse();

		// The next save anywhere republishes the document; the resolver reads the absent block.
		settings.save(settings.get());

		assertThat(settings.get().getShifts()).isNull();
		assertThat(shiftSettings.configured()).isFalse();
		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(true))
				.isFalse();
		assertThat(get("/api/v1/shifts", login()).statusCode()).isEqualTo(404);
	}

	// --- helpers ---------------------------------------------------------------

	/** Through the admin PUT, as the app does it: read the whole document, set one field, write it back. */
	private void enabledThroughAdmin(boolean value) {
		String token = login();
		ObjectNode document = (ObjectNode) body(get("/api/v1/admin/settings", token));
		((ObjectNode) document.path("shifts")).put("enabled", value);
		HttpResponse<String> saved = putJson("/api/v1/admin/settings", document.toString(), token);
		assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
	}

	private static List<String> concat(List<String> first, List<String> second) {
		return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
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

	private HttpResponse<String> putJson(String path, String json, String token) {
		return send(HttpRequest.newBuilder(url(path))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.PUT(HttpRequest.BodyPublishers.ofString(json)).build());
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
