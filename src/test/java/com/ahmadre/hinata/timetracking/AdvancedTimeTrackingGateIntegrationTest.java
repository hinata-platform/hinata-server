package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client sees while the extended module is off, and what changes the
 * moment an administrator turns it on.
 *
 * <p>Over real HTTP, because everything that matters here is layering: the
 * request has to pass the security filter chain first (so the answer is 404 for
 * someone signed in, not 401), the interceptor has to run even though no
 * controller is mapped under the prefix yet, and the refusal has to come back as
 * the ordinary localized error body rather than a container's default page. None
 * of that is visible from a unit test of the interceptor.
 *
 * <p>The probe controller stands in for the handlers stage 3 will put behind the
 * gate. Without it "the module is on again" could only be asserted as a
 * different flavour of 404, which is exactly the ambiguity worth removing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// The shipped default, stated rather than assumed: this whole file is
		// about what an instance that has not opted in looks like.
		"hinata.time-tracking.advanced-enabled=false"
})
@Import(AdvancedTimeTrackingGateIntegrationTest.GatedProbe.class)
@Testcontainers(disabledWithoutDocker = true)
class AdvancedTimeTrackingGateIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";

	/** One handler per gated prefix, so "the gate opened" can be read as a 200. */
	@TestConfiguration
	static class GatedProbe {

		@RestController
		static class Probe {

			@GetMapping({ "/api/v1/time/probe", "/api/v1/me/timer", "/api/v1/availability/probe",
					"/api/v1/billing/probe", "/api/v1/me/calendar-subscriptions" })
			Map<String, String> probe() {
				return Map.of("reached", "yes");
			}
		}
	}

	/** Every path the gate covers, one per prefix the epic reserves for the module. */
	private static final List<String> GATED = List.of(
			"/api/v1/time/probe",
			"/api/v1/me/timer",
			"/api/v1/availability/probe",
			"/api/v1/billing/probe",
			"/api/v1/me/calendar-subscriptions");

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private SettingsService settings;

	@BeforeAll
	static void dockerImagePinned() {
		assertThat(MONGO.getDockerImageName()).contains("mongo:8.0");
	}

	@AfterEach
	void clearOverride() {
		// Every test starts from "no admin has an opinion", so the env default is
		// what decides — the state a fresh instance is actually in.
		ServerSettings current = settings.get();
		current.setTimeTracking(null);
		settings.save(current);
	}

	// --- HTTP helpers ------------------------------------------------------

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
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

	private HttpResponse<String> putJson(String path, String body, String token) {
		return send(HttpRequest.newBuilder(url(path))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.header("Authorization", "Bearer " + token)
				.PUT(HttpRequest.BodyPublishers.ofString(body)).build());
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

	private void enableModule(boolean enabled) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(enabled);
		current.setTimeTracking(block);
		// save() publishes SettingsChangedEvent — the same path the admin PUT
		// takes, and the only thing between "an admin flipped a switch" and "the
		// next request is answered differently". No restart in sight.
		settings.save(current);
	}

	// --- the flag off ------------------------------------------------------

	@Test
	@DisplayName("with nothing configured, /meta says the module is off")
	void metaPublishesTheFlagAsOff() {
		JsonNode meta = body(get("/api/v1/meta", null, null));

		assertThat(meta.path("featureFlags").path("advanced_time_tracking").asBoolean(true)).isFalse();
		// The flag that was already there keeps its own value; adding a module flag
		// must not disturb the map the published app reads.
		assertThat(meta.path("featureFlags").has("mcp")).isTrue();
	}

	@Test
	@DisplayName("every extended prefix answers 404 error.feature.disabled")
	void gatedPrefixesAreNotFoundWhileTheModuleIsOff() {
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
	void theRefusalIsTranslated() {
		JsonNode german = body(get("/api/v1/time/probe", login(), "de"));

		assertThat(german.path("status").asInt()).isEqualTo(404);
		assertThat(german.path("message").asText())
				.isEqualTo("Diese Funktion ist auf diesem Server nicht aktiviert");
	}

	@Test
	@DisplayName("a caller who is not signed in still gets 401, not a hint that the module exists")
	void anonymousCallersLearnNothing() {
		assertThat(get("/api/v1/time/probe", null, null).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("the base time-tracking routes are untouched by the gate")
	void baseRoutesKeepAnswering() {
		String token = login();
		String projectId = body(get("/api/v1/projects", token, null)).get(0).path("id").asText();
		String issueId = body(get("/api/v1/issues?page=0&size=1", token, null))
				.path("content").get(0).path("id").asText();
		LocalDate today = LocalDate.now();
		String range = "from=" + today.minusDays(7) + "&to=" + today;

		// The 1.x contract the published app speaks. It predates the flag and is
		// not behind it: switching the module off must not take away the time
		// tracking an instance already had.
		assertThat(get("/api/v1/issues/" + issueId + "/work-items", token, null).statusCode())
				.as("work items of an issue").isEqualTo(200);
		assertThat(get("/api/v1/issues/" + issueId + "/work-items/page", token, null).statusCode())
				.as("work items, paged").isEqualTo(200);
		assertThat(get("/api/v1/timesheet?" + range, token, null).statusCode())
				.as("timesheet").isEqualTo(200);
		assertThat(get("/api/v1/reports/time-per-project?" + range, token, null).statusCode())
				.as("time per project").isEqualTo(200);
		assertThat(get("/api/v1/reports/time-per-activity?projectId=" + projectId + "&" + range,
				token, null).statusCode()).as("time per activity").isEqualTo(200);
		assertThat(get("/api/v1/reports/issues-by-state?projectId=" + projectId, token, null)
				.statusCode()).as("issues by state").isEqualTo(200);
	}

	// --- turning it on and off again ---------------------------------------

	@Test
	@DisplayName("an admin turning the module on takes effect on the next request")
	void togglingTheModuleNeedsNoRestart() {
		String token = login();
		assertThat(get("/api/v1/time/probe", token, null).statusCode()).isEqualTo(404);

		enableModule(true);

		assertThat(body(get("/api/v1/meta", null, null))
				.path("featureFlags").path("advanced_time_tracking").asBoolean()).isTrue();
		for (String path : GATED) {
			assertThat(get(path, token, null).statusCode()).as(path).isEqualTo(200);
		}

		enableModule(false);

		assertThat(get("/api/v1/time/probe", token, null).statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("with the module on, an unmapped path under the prefix is an ordinary 404")
	void theGateDoesNotInventHandlers() {
		String token = login();
		enableModule(true);

		HttpResponse<String> response = get("/api/v1/time/nothing-here", token, "en");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).doesNotContain("not enabled on this server");
	}

	// --- the admin form ----------------------------------------------------

	@Test
	@DisplayName("the admin settings carry the effective policy, and a save keeps it")
	void adminSettingsRoundTripTheNewSection() {
		String token = login();
		JsonNode fetched = body(get("/api/v1/admin/settings", token, null));
		JsonNode block = fetched.path("timeTracking");

		// What is in force is reported, in a block of its own.
		JsonNode effective = block.path("effective");
		assertThat(effective.path("advancedEnabled").asBoolean(true)).isFalse();
		assertThat(effective.path("approvalPeriod").path("type").asText()).isEqualTo("MONTHLY");
		assertThat(effective.path("currency").asText()).isEqualTo("EUR");
		assertThat(effective.path("leadsSeeMemberEntries").asBoolean(true)).isFalse();

		// And the editable fields stay empty. This is the half that matters: the
		// client hands the whole document back on save, so anything filled in
		// here would be written to Mongo as an explicit override and the
		// instance would stop listening to its own HINATA_TIME_TRACKING_* vars.
		assertThat(block.path("currency").isNull()).isTrue();
		assertThat(block.path("leadsSeeMemberEntries").isNull()).isTrue();
		// The nested groups are not created either: an empty group is not the same
		// as an absent one to a Jackson record, and "no opinion" has to survive
		// the round trip whole.
		assertThat(block.path("approvalPeriod").isMissingNode()
				|| block.path("approvalPeriod").isNull()).isTrue();

		// What the published 10.3.3 client does: it holds the settings as the raw
		// map it read and hands the whole thing back, unknown sections included.
		ObjectNode echoed = (ObjectNode) fetched;
		((ObjectNode) echoed.path("timeTracking")).put("advancedEnabled", true);
		HttpResponse<String> saved = putJson("/api/v1/admin/settings", echoed.toString(), token);

		assertThat(saved.statusCode()).isEqualTo(200);
		assertThat(body(get("/api/v1/meta", null, null))
				.path("featureFlags").path("advanced_time_tracking").asBoolean()).isTrue();

		// The save changed one switch and adopted nothing else. An operator who
		// later clears HINATA_TIME_TRACKING_CURRENCY still sees the new default,
		// because this instance never claimed an opinion about it.
		ServerSettings stored = settings.get();
		assertThat(stored.getTimeTracking().getAdvancedEnabled()).isTrue();
		assertThat(stored.getTimeTracking().getCurrency()).isNull();
		assertThat(stored.getTimeTracking().getLeadsSeeMemberEntries()).isNull();
		// And the read-only view is not what got written.
		assertThat(stored.getTimeTracking().getEffective()).isNull();
	}

	@Test
	@DisplayName("a client that drops the unknown section does not switch the module off")
	void aSaveWithoutTheSectionKeepsWhatIsStored() {
		String token = login();
		enableModule(true);

		ObjectNode withoutBlock = (ObjectNode) body(get("/api/v1/admin/settings", token, null));
		withoutBlock.remove("timeTracking");
		assertThat(putJson("/api/v1/admin/settings", withoutBlock.toString(), token).statusCode())
				.isEqualTo(200);

		// An omitted section is "no opinion", not "erase it". Anything else would
		// let an old client silently take the module away from everyone.
		assertThat(body(get("/api/v1/meta", null, null))
				.path("featureFlags").path("advanced_time_tracking").asBoolean()).isTrue();
		assertThat(get("/api/v1/time/probe", token, null).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("an approval period whose parameters do not fit is refused")
	void anIncoherentApprovalPeriodIsRejected() {
		String token = login();
		ObjectNode document = (ObjectNode) body(get("/api/v1/admin/settings", token, null));
		// The admin area creates the group when an operator first sets a rhythm —
		// the server sends none, because it stores no opinion of its own.
		ObjectNode period = ((ObjectNode) document.path("timeTracking"))
				.putObject("approvalPeriod");
		period.put("type", "BIWEEKLY");
		period.putNull("anchorDate");

		HttpResponse<String> response =
				putJson("/api/v1/admin/settings", document.toString(), token);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(body(response).path("fieldErrors")
				.path("timeTracking.approvalPeriod.anchorDate").asText())
				.isEqualTo("error.timeTracking.approvalPeriodInvalid");
	}
}
