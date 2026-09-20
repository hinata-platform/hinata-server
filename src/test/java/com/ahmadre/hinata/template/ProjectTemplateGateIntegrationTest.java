package com.ahmadre.hinata.template;

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
 * What a client sees while project templates are off, and what it takes to turn them on.
 *
 * <p>Two things are being tested and the second one matters more. The first is the ordinary
 * refusal: the module's own routes answer 404 {@code error.feature.disabled}, localized like every
 * other error, so the app can tell this apart from a genuine not-found and re-read {@code /meta}.
 *
 * <p>The second is what stays open. The gate hangs on four sub-paths of
 * {@code /api/v1/projects/…}, not on the collection, so a project can still be read, renamed and
 * archived on an instance that will never copy one. A gate that took the whole prefix would turn a
 * disabled feature into a broken product, and the shipped store app would lose projects entirely.
 *
 * <p>Over real HTTP, because the layering is the subject: the request passes the security chain
 * first, the interceptor answers before any handler is reached, and the refusal comes back as the
 * ordinary localized error body. The probe below stands in for the module's own handlers so that
 * "the gate opened" reads as a 200 rather than as a different 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// The shipped default, stated rather than assumed: this file is about the instance
		// nobody has opted in on.
		"hinata.project-templates.enabled=false"
})
@Import(ProjectTemplateGateIntegrationTest.GatedProbe.class)
@Testcontainers(disabledWithoutDocker = true)
class ProjectTemplateGateIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";
	private static final String FLAG = "project_templates";

	/** Handlers behind every gated path, so "the gate opened" reads as a 200 rather than as a different 404. */
	@TestConfiguration
	static class GatedProbe {

		@RestController
		static class Probe {

			@GetMapping({ "/api/v1/projects/PROBE/copy", "/api/v1/projects/PROBE/instantiate",
					"/api/v1/projects/PROBE/schedule", "/api/v1/projects/PROBE/schedule/preview" })
			Map<String, String> probe() {
				return Map.of("reached", "yes");
			}
		}
	}

	/** Every gated path, including both forms of {@code /schedule}: {@code /a/**} does not match {@code /a}. */
	private static final List<String> GATED = List.of(
			"/api/v1/projects/PROBE/copy",
			"/api/v1/projects/PROBE/instantiate",
			"/api/v1/projects/PROBE/schedule",
			"/api/v1/projects/PROBE/schedule/preview");

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
		current.setProjectTemplates(null);
		settings.save(current);
	}

	// --- the flag off --------------------------------------------------------

	@Test
	@DisplayName("with nothing configured, /meta says project templates are off")
	void metaPublishesTheFlagAsOff() {
		JsonNode flags = body(get("/api/v1/meta", null)).path("featureFlags");

		assertThat(flags.path(FLAG).asBoolean(true)).isFalse();
		// The flags that were already there keep their own values; a new module flag must not
		// disturb the map the published app reads.
		assertThat(flags.has("advanced_time_tracking")).isTrue();
		assertThat(flags.has("mcp")).isTrue();
	}

	@Test
	@DisplayName("every gated path answers 404 error.feature.disabled")
	void theGatedPathsAreNotFoundWhileTheModuleIsOff() {
		String token = login();

		for (String path : GATED) {
			HttpResponse<String> response = get(path, token, "en");
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			assertThat(body(response).path("message").asText()).as(path)
					.isEqualTo("This feature is not enabled on this server");
		}
	}

	@Test
	@DisplayName("the refusal is localized like every other error")
	void theRefusalSpeaksTheRequestLanguage() {
		HttpResponse<String> response = get("/api/v1/projects/PROBE/copy", login(), "de");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(body(response).path("message").asText())
				.isEqualTo("Diese Funktion ist auf diesem Server nicht aktiviert");
	}

	@Test
	@DisplayName("the ordinary project and issue routes are untouched by the switch")
	void theExistingRoutesStayOpen() {
		String token = login();

		// The list a client reads on every start, and one project in full. Neither is the
		// module's, and neither may depend on it.
		HttpResponse<String> projects = get("/api/v1/projects", token);
		assertThat(projects.statusCode()).isEqualTo(200);
		JsonNode items = body(projects);
		assertThat(items.isArray()).isTrue();
		assertThat(items).isNotEmpty();

		String id = items.get(0).path("id").asText();
		assertThat(get("/api/v1/projects/" + id, token).statusCode()).isEqualTo(200);
		assertThat(get("/api/v1/issues?projectId=" + id, token).statusCode()).isEqualTo(200);
	}

	// --- the switch ----------------------------------------------------------

	@Test
	@DisplayName("an administrator turns it on and the next request comes through, no restart")
	void theSwitchOpensAndClosesTheModule() {
		enabled(true);
		String token = login();

		assertThat(body(get("/api/v1/meta", null)).path("featureFlags").path(FLAG).asBoolean(false))
				.isTrue();
		for (String path : GATED) {
			HttpResponse<String> response = get(path, token);
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			assertThat(body(response).path("reached").asText()).as(path).isEqualTo("yes");
		}

		enabled(false);

		for (String path : GATED) {
			assertThat(get(path, token).statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("an anonymous caller is refused before the gate is reached")
	void authenticationComesFirst() {
		enabled(true);

		assertThat(get("/api/v1/projects/PROBE/copy", null).statusCode()).isEqualTo(401);
	}

	// --- helpers -------------------------------------------------------------

	/** Stores the switch; the save publishes the event the resolver listens for. */
	private void enabled(Boolean value) {
		ServerSettings current = settings.get();
		ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
		block.setEnabled(value);
		current.setProjectTemplates(block);
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
