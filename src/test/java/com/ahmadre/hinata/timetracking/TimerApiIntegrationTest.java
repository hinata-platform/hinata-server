package com.ahmadre.hinata.timetracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timer and the entry list as a client actually meets them: status codes,
 * response shapes and the live event.
 *
 * <p>Over real HTTP, because that is where the parts this stage adds are
 * visible and nowhere else. "No timer" is a 204 with no body — a service test
 * sees an empty {@code Optional} and cannot tell you what the client receives.
 * A second start is a 409 carrying the module's own message key, which only
 * exists once the exception has been through the handler and the message
 * bundle. And the {@code timer} frame is an SSE event on a stream the service
 * knows nothing about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class TimerApiIntegrationTest {

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

	@Autowired
	private MongoTemplate mongo;

	private String token;

	@BeforeAll
	static void dockerImagePinned() {
		assertThat(MONGO.getDockerImageName()).contains("mongo:8.0");
	}

	@BeforeEach
	void signIn() {
		mongo.getCollection("running_timers").deleteMany(new org.bson.Document());
		token = login();
	}

	// --- HTTP helpers ---------------------------------------------------------

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + request.uri(), ex);
		}
	}

	private HttpResponse<String> get(String path) {
		return send(HttpRequest.newBuilder(url(path)).GET()
				.header("Authorization", "Bearer " + token).build());
	}

	private HttpResponse<String> post(String path, String body) {
		return send(HttpRequest.newBuilder(url(path))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.header("Authorization", "Bearer " + token)
				.header("Accept-Language", "en")
				.POST(body == null ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(body))
				.build());
	}

	private HttpResponse<String> patch(String path, String body) {
		return send(HttpRequest.newBuilder(url(path))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.header("Authorization", "Bearer " + token)
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build());
	}

	private HttpResponse<String> delete(String path) {
		return send(HttpRequest.newBuilder(url(path)).DELETE()
				.header("Authorization", "Bearer " + token).build());
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

	// --- the timer -------------------------------------------------------------

	@Test
	@DisplayName("no timer is 204 with no body, not 404 and not an empty object")
	void nothingRunning() {
		HttpResponse<String> response = get("/api/v1/me/timer");

		// 204 rather than 404: the resource exists and is empty. A 404 here would
		// be indistinguishable from the module being switched off, which is the
		// one thing this route must never be ambiguous about.
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
	}

	@Test
	@DisplayName("start, read, patch and stop, end to end")
	void theWholeRoundTrip() {
		JsonNode started = body(post("/api/v1/me/timer/start",
				"{\"description\":\"Pairing\",\"activityType\":\"Development\"}"));
		assertThat(started.path("id").asText()).isNotBlank();
		assertThat(started.path("startedAt").asText()).isNotBlank();
		assertThat(started.path("mode").asText()).isEqualTo("STOPWATCH");
		// The owner is who is asking; the wire does not repeat it back.
		assertThat(started.has("userId")).isFalse();

		assertThat(get("/api/v1/me/timer").statusCode()).isEqualTo(200);
		assertThat(body(get("/api/v1/me/timer")).path("description").asText()).isEqualTo("Pairing");

		JsonNode patched = body(patch("/api/v1/me/timer", "{\"description\":\"Reviewing\"}"));
		assertThat(patched.path("description").asText()).isEqualTo("Reviewing");
		assertThat(patched.path("startedAt").asText())
				.as("the clock does not restart").isEqualTo(started.path("startedAt").asText());

		HttpResponse<String> stopped = post("/api/v1/me/timer/stop", "{}");
		assertThat(stopped.statusCode()).isEqualTo(200);
		JsonNode entry = body(stopped).path("entry");
		assertThat(entry.path("id").asText()).isEqualTo(started.path("id").asText());
		assertThat(entry.path("source").asText()).isEqualTo("TIMER");
		assertThat(entry.path("description").asText()).isEqualTo("Reviewing");
		assertThat(body(stopped).path("overlaps").isArray()).isTrue();
		assertThat(get("/api/v1/me/timer").statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("starting with no body at all is allowed")
	void startNeedsNothing() {
		assertThat(post("/api/v1/me/timer/start", null).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("a second start is a 409 with the module's own message")
	void secondStartConflicts() {
		post("/api/v1/me/timer/start", "{}");

		HttpResponse<String> second = post("/api/v1/me/timer/start", "{}");

		assertThat(second.statusCode()).isEqualTo(409);
		assertThat(body(second).path("message").asText()).isEqualTo("A timer is already running");
	}

	@Test
	@DisplayName("discarding answers 204 and records nothing")
	void discard() {
		post("/api/v1/me/timer/start", "{\"description\":\"scrap\"}");

		assertThat(post("/api/v1/me/timer/discard", null).statusCode()).isEqualTo(204);

		assertThat(get("/api/v1/me/timer").statusCode()).isEqualTo(204);
		assertThat(body(get("/api/v1/time/entries?q=scrap")).path("totalElements").asInt())
				.isZero();
	}

	@Test
	@DisplayName("a description longer than the limit is refused before anything is stored")
	void oversizedDescription() {
		String tooLong = "x".repeat(2001);

		HttpResponse<String> response = post("/api/v1/me/timer/start",
				"{\"description\":\"" + tooLong + "\"}");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(get("/api/v1/me/timer").statusCode()).isEqualTo(204);
	}

	// --- entries ---------------------------------------------------------------

	@Test
	@DisplayName("an entry posts as an entry plus the overlaps noticed while saving")
	void createReturnsTheAdvice() {
		String day = todayOnTheServer();

		JsonNode first = body(post("/api/v1/time/entries", """
				{"durationMinutes":60,"date":"%s","description":"first",
				 "startedAt":"%sT09:00:00Z","endedAt":"%sT10:00:00Z"}
				""".formatted(day, day, day)));
		JsonNode second = body(post("/api/v1/time/entries", """
				{"date":"%s","description":"second",
				 "startedAt":"%sT09:30:00Z","endedAt":"%sT10:30:00Z"}
				""".formatted(day, day, day)));

		assertThat(first.path("overlaps")).isEmpty();
		// Saved anyway — the advice is advice.
		assertThat(second.path("entry").path("id").asText()).isNotBlank();
		assertThat(second.path("entry").path("durationMinutes").asInt()).isEqualTo(60);
		assertThat(second.path("overlaps")).hasSize(1);
		assertThat(second.path("overlaps").get(0).asText())
				.isEqualTo(first.path("entry").path("id").asText());
	}

	@Test
	@DisplayName("the list is a page, and its filters travel in the query string")
	void theListIsAPage() {
		String day = todayOnTheServer();
		post("/api/v1/time/entries",
				"{\"durationMinutes\":30,\"date\":\"%s\",\"description\":\"needle\"}".formatted(day));

		JsonNode page = body(get("/api/v1/time/entries?page=0&size=5"));
		assertThat(page.path("content").isArray()).isTrue();
		assertThat(page.has("totalElements")).isTrue();
		assertThat(page.path("size").asInt()).isEqualTo(5);

		JsonNode filtered = body(get("/api/v1/time/entries?q=needle"));
		assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
		assertThat(filtered.path("content").get(0).path("description").asText())
				.isEqualTo("needle");
	}

	@Test
	@DisplayName("a page larger than the ceiling is clamped, not honoured")
	void theSizeIsClamped() {
		JsonNode page = body(get("/api/v1/time/entries?size=5000"));

		assertThat(page.path("size").asInt()).isEqualTo(TimeTrackingService.PAGE_MAX);
	}

	@Test
	@DisplayName("an entry is edited and deleted through the module's own routes")
	void editAndDelete() {
		String day = todayOnTheServer();
		String id = body(post("/api/v1/time/entries",
				"{\"durationMinutes\":30,\"date\":\"%s\",\"description\":\"draft\"}".formatted(day)))
				.path("entry").path("id").asText();

		JsonNode patched = body(patch("/api/v1/time/entries/" + id,
				"{\"description\":\"final\",\"durationMinutes\":45}"));
		assertThat(patched.path("entry").path("description").asText()).isEqualTo("final");
		assertThat(patched.path("entry").path("durationMinutes").asInt()).isEqualTo(45);

		assertThat(delete("/api/v1/time/entries/" + id).statusCode()).isEqualTo(204);
		assertThat(body(get("/api/v1/time/entries?q=final")).path("totalElements").asInt()).isZero();
	}

	@Test
	@DisplayName("continuing an entry answers 201 with a timer")
	void continueAnEntry() {
		String day = todayOnTheServer();
		String id = body(post("/api/v1/time/entries",
				"{\"durationMinutes\":30,\"date\":\"%s\",\"description\":\"again\"}".formatted(day)))
				.path("entry").path("id").asText();

		HttpResponse<String> response = post("/api/v1/time/entries/" + id + "/continue", null);

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(body(response).path("description").asText()).isEqualTo("again");
		assertThat(body(get("/api/v1/me/timer")).path("description").asText()).isEqualTo("again");
	}

	// --- the live event ----------------------------------------------------------

	@Test
	@DisplayName("a second device hears the timer start and stop, and can reconcile by asking")
	void theTimerEventReachesTheOtherDevice() throws Exception {
		BlockingQueue<String> frames = new LinkedBlockingQueue<>();
		HttpResponse<Stream<String>> stream = http.send(
				HttpRequest.newBuilder(url("/api/v1/me/stream"))
						.header("Authorization", "Bearer " + token)
						.header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
						.GET().build(),
				HttpResponse.BodyHandlers.ofLines());
		assertThat(stream.statusCode()).isEqualTo(200);
		Thread reader = Thread.ofVirtual().start(() -> stream.body().forEach(frames::add));
		try {
			// The stream opens with a comment; wait for it so the subscriber is
			// registered before anything is published. Without this the test would
			// race the registration and pass or fail by timing.
			assertThat(await(frames, line -> line.startsWith(":"))).isTrue();

			post("/api/v1/me/timer/start", "{\"description\":\"on the other device\"}");
			assertThat(await(frames, line -> line.equals("event:timer"))).isTrue();
			assertThat(await(frames, line -> line.startsWith("data:") && line.contains("\"running\":true")))
					.isTrue();

			post("/api/v1/me/timer/stop", "{}");
			assertThat(await(frames, line -> line.startsWith("data:") && line.contains("\"running\":false")))
					.isTrue();

			// What the client does on reconnect: it does not trust the frame, it
			// asks. The two must agree, which is the whole contract of a
			// best-effort single-instance event.
			assertThat(get("/api/v1/me/timer").statusCode()).isEqualTo(204);
		}
		finally {
			reader.interrupt();
		}
	}

	/** Waits up to ten seconds for a frame matching {@code match}, draining the rest. */
	private boolean await(BlockingQueue<String> frames, java.util.function.Predicate<String> match)
			throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			String line = frames.poll(500, TimeUnit.MILLISECONDS);
			if (line != null && match.test(line)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Today as the server reads it. The seeded admin has no time zone, so the
	 * instance default decides, and an entry dated by the test's own clock would
	 * be "in the future" for half the day in some of them.
	 */
	private String todayOnTheServer() {
		return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
	}
}
