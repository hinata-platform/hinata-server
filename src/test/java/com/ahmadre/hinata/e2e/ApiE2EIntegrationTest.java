package com.ahmadre.hinata.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-stack, black-box end-to-end integration test: boots the entire
 * application (real Spring context + embedded Tomcat on a random port) against a
 * throwaway, production-parity MongoDB {@link MongoDBContainer} wired in via
 * {@link ServiceConnection}. Nothing is mocked — a plain JDK {@link HttpClient}
 * drives requests over real HTTP, exercising the controller → service → Spring
 * Security → MongoDB path exactly as a client would.
 *
 * <p>This is the reliability backstop for the build/runtime: if the packaged app
 * cannot authenticate, authorize and serve seeded data end to end, this test
 * fails. It is skipped automatically on machines without Docker
 * ({@code disabledWithoutDocker = true}) so the fast unit suite still runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		// Plain Mongo container — no X.509/TLS in tests (prod uses X.509).
		"hinata.mongodb.tls.enabled=false",
		// Never make outbound calls to Hinata Connect from a test.
		"hinata.gateway.enabled=false",
		// Deterministic dataset: wipe + re-seed the demo workspace on boot.
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		// Rate limiter off so repeated logins in the suite aren't throttled.
		"hinata.rate-limit.enabled=false",
		// The built-in mail health indicator pings SMTP (spring.mail.host defaults
		// to localhost:1025). No SMTP server exists in a clean CI environment, which
		// would flip /actuator/health to DOWN. Mail reachability is not what this
		// test verifies (prod runs real SMTP), so exclude it from the aggregate.
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ApiE2EIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@BeforeAll
	static void dockerImagePinned() {
		// Fail loudly rather than silently pulling :latest.
		assertThat(MONGO.getDockerImageName()).contains("mongo:8.0");
	}

	// --- HTTP helpers ------------------------------------------------------

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private JsonNode parse(String body) {
		try {
			return JSON.readTree(body);
		}
		catch (Exception e) {
			throw new AssertionError("Response was not valid JSON: " + body, e);
		}
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception e) {
			throw new AssertionError("HTTP request failed: " + request.uri(), e);
		}
	}

	private HttpResponse<String> get(String path, String token) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET()
				.timeout(Duration.ofSeconds(15));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private HttpResponse<String> postJson(String path, String json, String token) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private String login(String identifier, String password) {
		HttpResponse<String> res = postJson("/api/v1/auth/login",
				"{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}", null);
		assertThat(res.statusCode()).as("login status").isEqualTo(200);
		JsonNode body = parse(res.body());
		assertThat(body.path("mfaRequired").asBoolean()).isFalse();
		String token = body.path("accessToken").asText(null);
		assertThat(token).as("access token").isNotBlank();
		return token;
	}

	private JsonNode getOk(String path, String token) {
		HttpResponse<String> res = get(path, token);
		assertThat(res.statusCode()).as("GET %s", path).isEqualTo(200);
		return parse(res.body());
	}

	// --- tests -------------------------------------------------------------

	@Test
	@DisplayName("actuator health is UP against the real Mongo container")
	void healthIsUp() {
		HttpResponse<String> res = get("/actuator/health", null);
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(parse(res.body()).path("status").asText()).isEqualTo("UP");
	}

	@Test
	@DisplayName("login issues a JWT and reports the admin role")
	void loginReturnsJwtWithAdminRole() {
		String token = login(ADMIN_USER, ADMIN_PASS);
		JsonNode me = getOk("/api/v1/auth/me", token);
		assertThat(me.path("username").asText()).isEqualTo(ADMIN_USER);
		assertThat(me.path("roles").isArray()).isTrue();
		assertThat(me.path("roles").toString()).contains("ADMIN");
	}

	@Test
	@DisplayName("protected endpoints reject requests without a token (401)")
	void protectedEndpointRejectsAnonymous() {
		assertThat(get("/api/v1/auth/me", null).statusCode()).isEqualTo(401);
		// Team/project avatars are byte proxies like the user avatar, but they are
		// deliberately NOT permit-listed: a public one would tell anyone which
		// internal teams and projects exist, and what their logos look like.
		assertThat(get("/api/v1/teams/anything/avatar", null).statusCode()).isEqualTo(401);
		assertThat(get("/api/v1/projects/anything/avatar", null).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("bad credentials are rejected (401)")
	void badCredentialsRejected() {
		HttpResponse<String> res = postJson("/api/v1/auth/login",
				"{\"identifier\":\"admin\",\"password\":\"wrong\"}", null);
		assertThat(res.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("seeded projects are served through the real stack")
	void seededProjectsAreServed() {
		JsonNode projects = getOk("/api/v1/projects", login(ADMIN_USER, ADMIN_PASS));
		assertThat(projects.isArray()).isTrue();
		assertThat(projects.size()).as("seeded projects").isGreaterThanOrEqualTo(3);
		assertThat(projects.get(0).path("key").asText()).isNotBlank();
	}

	@Test
	@DisplayName("issue listing is paginated with a stable total")
	void issuesArePaginated() {
		JsonNode page = getOk("/api/v1/issues?page=0&size=5", login(ADMIN_USER, ADMIN_PASS));
		assertThat(page.path("content").size()).isEqualTo(5);
		assertThat(page.path("totalElements").asInt())
				.as("seeded issues").isGreaterThanOrEqualTo(50);
	}

	@Test
	@DisplayName("create → read-back proves the write path through real Mongo")
	void createAndReadBackProject() {
		String token = login(ADMIN_USER, ADMIN_PASS);
		HttpResponse<String> created = postJson("/api/v1/projects",
				"{\"key\":\"E2E\",\"name\":\"E2E Integration Project\"}", token);
		assertThat(created.statusCode()).as("create project (2xx)").isBetween(200, 201);
		String id = parse(created.body()).path("id").asText(null);
		assertThat(id).as("created project id").isNotBlank();

		JsonNode readBack = getOk("/api/v1/projects/" + id, token);
		assertThat(readBack.path("name").asText()).isEqualTo("E2E Integration Project");
	}

	@Test
	@DisplayName("a board reads in pages, with its search and filter bound from the query string")
	void boardReadsInPages() {
		String token = login(ADMIN_USER, ADMIN_PASS);
		String projectId = getOk("/api/v1/projects", token).get(0).path("id").asText();
		HttpResponse<String> created = postJson("/api/v1/boards",
				"{\"name\":\"E2E board\",\"projectIds\":[\"" + projectId + "\"]}", token);
		assertThat(created.statusCode()).as("create board").isEqualTo(201);
		String boards = "/api/v1/boards/" + parse(created.body()).path("id").asText();

		JsonNode wall = getOk(boards + "/wall?size=2", token);
		JsonNode busiest = null;
		for (JsonNode column : wall.path("columns")) {
			assertThat(column.path("issues").size()).isLessThanOrEqualTo(2);
			if (busiest == null || column.path("total").asLong() > busiest.path("total").asLong()) {
				busiest = column;
			}
		}
		assertThat(busiest).as("a column").isNotNull();
		assertThat(busiest.path("total").asLong()).as("cards of the seeded project").isPositive();
		JsonNode card = busiest.path("issues").get(0);
		assertThat(card.path("readableId").asText()).isNotBlank();
		// A card is the slim shape: nothing of the description travels with it.
		assertThat(card.has("description")).isFalse();
		assertThat(card.has("descriptionDoc")).isFalse();

		String column = URLEncoder.encode(busiest.path("name").asText(), StandardCharsets.UTF_8);
		JsonNode page = getOk(boards + "/cards?column=" + column + "&page=0&size=1", token);
		assertThat(page.path("totalElements").asLong()).isEqualTo(busiest.path("total").asLong());
		assertThat(page.path("content").size()).isEqualTo(1);

		// Lists bind from repeated parameters, and the search from q.
		String state = URLEncoder.encode(busiest.path("states").get(0).asText(), StandardCharsets.UTF_8);
		JsonNode filtered = getOk(boards + "/cards?states=" + state + "&states=NOPE&types=TASK&types=BUG"
				+ "&q=" + URLEncoder.encode(card.path("title").asText(), StandardCharsets.UTF_8) + "&size=100", token);
		assertThat(filtered.path("content").isArray()).isTrue();
		assertThat(getOk(boards + "/facets", token).path("states").isArray()).isTrue();

		assertThat(get(boards + "/cards?shape=kanban", token).statusCode()).isEqualTo(400);
		assertThat(get(boards + "/cards?column=Nowhere", token).statusCode()).isEqualTo(400);
		assertThat(get(boards + "/wall", null).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("issues-by-state report aggregates real data")
	void reportAggregatesRealData() {
		String token = login(ADMIN_USER, ADMIN_PASS);
		String projectId = getOk("/api/v1/projects", token).get(0).path("id").asText();
		JsonNode report = getOk("/api/v1/reports/issues-by-state?projectId=" + projectId, token);
		assertThat(report.isObject()).isTrue();
		int total = 0;
		for (JsonNode count : report) {
			total += count.asInt();
		}
		assertThat(total).as("aggregated issue count for the project").isPositive();
	}
}
