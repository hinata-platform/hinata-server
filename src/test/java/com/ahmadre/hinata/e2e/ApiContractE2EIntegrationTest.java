package com.ahmadre.hinata.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * Black-box end-to-end coverage for the search, directory, knowledge-base and
 * SSO HTTP contracts, exercised against a real MongoDB container and the seeded
 * demo workspace (projects HIN/MOB/INF, issues in "In Progress"/"Open"/… states,
 * KB articles that reference {@code {{issue:HIN-n}}}). Verifies end to end:
 *
 * <ul>
 *   <li>multi-facet, case-insensitive issue search;</li>
 *   <li>issue mention-search + readable-id resolve;</li>
 *   <li>bounded user directory + search + by-ids;</li>
 *   <li>article backlinks via {@code ?referencesIssue};</li>
 *   <li>notification/article response shapes; list filtering; SSO code exchange.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ApiContractE2EIntegrationTest {

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

	// --- helpers (mirror ApiE2EIntegrationTest) ---------------------------------

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
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET().timeout(Duration.ofSeconds(15));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private HttpResponse<String> postJson(String path, String json, String token) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private HttpResponse<String> delete(String path, String token) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15))
				.DELETE();
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private String login() {
		HttpResponse<String> res = postJson("/api/v1/auth/login",
				"{\"identifier\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}", null);
		assertThat(res.statusCode()).as("login").isEqualTo(200);
		return parse(res.body()).path("accessToken").asText();
	}

	private JsonNode getOk(String path, String token) {
		HttpResponse<String> res = get(path, token);
		assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isEqualTo(200);
		return parse(res.body());
	}

	// --- multi-facet, case-insensitive issue search --------------------

	@Test
	@DisplayName("state facet is case-insensitive and reduces server-side")
	void issueSearchByStateCaseInsensitive() {
		String token = login();
		// The client sends UPPER-CASE facet codes; the stored state is "In Progress".
		JsonNode page = getOk("/api/v1/issues?states=IN%20PROGRESS&size=100", token);
		assertThat(page.path("content").size()).as("matched issues").isGreaterThan(0);
		for (JsonNode issue : page.path("content")) {
			assertThat(issue.path("state").asText()).isEqualToIgnoringCase("In Progress");
		}
	}

	@Test
	@DisplayName("priority + type facets filter server-side")
	void issueSearchByPriorityAndType() {
		String token = login();
		JsonNode byPriority = getOk("/api/v1/issues?priorities=MAJOR&size=100", token);
		assertThat(byPriority.path("content").size()).isGreaterThan(0);
		for (JsonNode issue : byPriority.path("content")) {
			assertThat(issue.path("priority").asText()).isEqualTo("MAJOR");
		}
		JsonNode byType = getOk("/api/v1/issues?types=STORY&size=100", token);
		for (JsonNode issue : byType.path("content")) {
			assertThat(issue.path("type").asText()).isEqualTo("STORY");
		}
	}

	@Test
	@DisplayName("multiple state values combine as OR")
	void issueSearchMultiState() {
		String token = login();
		JsonNode page = getOk("/api/v1/issues?states=IN%20PROGRESS&states=DONE&size=100", token);
		for (JsonNode issue : page.path("content")) {
			assertThat(issue.path("state").asText()).isIn("In Progress", "Done");
		}
	}

	// --- mention-search + resolve --------------------------------------

	@Test
	@DisplayName("mention-search returns capped lightweight refs with a type glyph")
	void mentionSearchReturnsRefs() {
		String token = login();
		JsonNode refs = getOk("/api/v1/issues/mention-search?q=HIN", token);
		assertThat(refs.isArray()).isTrue();
		assertThat(refs.size()).isGreaterThan(0).isLessThanOrEqualTo(20);
		JsonNode first = refs.get(0);
		assertThat(first.path("id").asText()).isNotBlank();
		assertThat(first.path("readableId").asText()).isNotBlank();
		assertThat(first.has("title")).isTrue();
		// The dropdown row picks its glyph from the issue type.
		assertThat(first.path("type").asText()).isNotBlank();
	}

	@Test
	@DisplayName("resolve returns full issues for chip + hover-card rendering")
	void resolveKeysReturnsFullIssues() {
		String token = login();
		JsonNode issues = getOk("/api/v1/issues/resolve?keys=HIN-1&keys=HIN-2", token);
		assertThat(issues.isArray()).isTrue();
		assertThat(issues.size()).isGreaterThan(0);
		for (JsonNode issue : issues) {
			assertThat(issue.path("readableId").asText()).startsWith("HIN-");
			// Full issue, not a lightweight ref: the hover card needs these.
			assertThat(issue.has("state")).isTrue();
			assertThat(issue.has("priority")).isTrue();
			assertThat(issue.has("type")).isTrue();
		}
	}

	// --- bounded directory + search + by-ids ---------------------------

	@Test
	@DisplayName("directory is an array; search is paged; by-ids resolves")
	void userDirectoryAndSearchAndByIds() {
		String token = login();
		JsonNode directory = getOk("/api/v1/users", token);
		assertThat(directory.isArray()).isTrue();
		assertThat(directory.size()).isGreaterThan(0).isLessThanOrEqualTo(500);
		String someId = directory.get(0).path("id").asText();

		JsonNode search = getOk("/api/v1/users/search?q=&page=0&size=5", token);
		assertThat(search.path("content").isArray()).isTrue();
		assertThat(search.has("totalElements")).isTrue();

		JsonNode byIds = getOk("/api/v1/users/by-ids?ids=" + someId, token);
		assertThat(byIds.isArray()).isTrue();
		assertThat(byIds.get(0).path("id").asText()).isEqualTo(someId);
	}

	// --- article backlinks ---------------------------------------------

	@Test
	@DisplayName("?referencesIssue returns only articles citing the key")
	void articlesReferencingIssue() {
		String token = login();
		JsonNode articles = getOk("/api/v1/articles?referencesIssue=HIN-1", token);
		assertThat(articles.isArray()).isTrue();
		assertThat(articles.size()).as("seeded backlink to HIN-1").isGreaterThan(0);
		for (JsonNode article : articles) {
			// The link is a node in the document now, so the backlink is resolved
			// from a derived index rather than from a token in the body. What the
			// body still carries is the readable key, and the document holds the
			// link the reader clicks.
			assertThat(article.path("content").asText()).contains("HIN-1");
			assertThat(article.path("contentDoc").asText())
					.as("the article ships the document the editor renders")
					.contains("\"smartlink\"").contains("HIN-1");
		}
		// An unknown / malformed key is safely ignored (no match, not an error).
		assertThat(getOk("/api/v1/articles?referencesIssue=ZZZ-999", token).size()).isEqualTo(0);
	}

	// --- response DTO shapes -------------------------------------------

	@Test
	@DisplayName("notification + article responses keep their wire shape")
	void responseDtoShapes() {
		String token = login();
		JsonNode notifications = getOk("/api/v1/notifications?page=0&size=5", token);
		assertThat(notifications.has("content")).isTrue();
		if (notifications.path("content").size() > 0) {
			JsonNode n = notifications.path("content").get(0);
			assertThat(n.has("id")).isTrue();
			assertThat(n.has("type")).isTrue();
			assertThat(n.has("read")).isTrue();
			assertThat(n.has("createdAt")).isTrue();
		}
		JsonNode articles = getOk("/api/v1/articles?all=true", token);
		assertThat(articles.isArray()).isTrue();
		if (articles.size() > 0) {
			JsonNode a = articles.get(0);
			assertThat(a.has("id")).isTrue();
			assertThat(a.has("title")).isTrue();
			assertThat(a.has("space")).isTrue();
		}
	}

	// --- list filtering ------------------------------------------------

	@Test
	@DisplayName("project list accepts an optional name filter")
	void projectListNameFilter() {
		String token = login();
		JsonNode all = getOk("/api/v1/projects", token);
		assertThat(all.size()).isGreaterThanOrEqualTo(3);
		JsonNode filtered = getOk("/api/v1/projects?q=Hinata", token);
		assertThat(filtered.isArray()).isTrue();
		for (JsonNode p : filtered) {
			String hay = (p.path("name").asText() + " " + p.path("key").asText()).toLowerCase();
			assertThat(hay).contains("hinata");
		}
	}

	// --- SSO exchange endpoint -----------------------------------------

	// --- watching an issue ------------------------------------------------------

	/**
	 * The three calls the app makes to subscribe, unsubscribe and list. Pinned as
	 * a contract because the client's whole watch toggle is built on them: 204 on
	 * both writes, a page on the read, and both writes idempotent so a retried
	 * request after a lost response cannot double-subscribe or 404.
	 */
	@Test
	@DisplayName("watch / unwatch are 204 and idempotent; /me/watched is a page")
	void watchUnwatchAndTheWatchedList() {
		String token = login();
		JsonNode issues = getOk("/api/v1/issues?size=1", token);
		String key = issues.path("content").get(0).path("readableId").asText();

		// Counts here are relative, never absolute. The demo seed subscribes this
		// account to a handful of issues so the app's Watched page has something
		// to show, and a test that asserts "exactly one" is really asserting that
		// nobody ever seeds a watcher — which is a fixture detail, not the
		// contract. What the client depends on is that one subscribe adds exactly
		// one row and one unsubscribe takes it away again.
		delete("/api/v1/issues/" + key + "/watch", token);   // known starting point
		int baseline = getOk("/api/v1/me/watched?page=0&size=1", token)
				.path("totalElements").asInt();

		assertThat(postJson("/api/v1/issues/" + key + "/watch", "", token).statusCode())
				.as("subscribe").isEqualTo(204);
		assertThat(postJson("/api/v1/issues/" + key + "/watch", "", token).statusCode())
				.as("subscribing again is a no-op, not a conflict").isEqualTo(204);

		JsonNode watched = getOk("/api/v1/me/watched?page=0&size=50", token);
		assertThat(watched.has("content")).as("a Spring page, like every other list").isTrue();
		assertThat(watched.path("totalElements").asInt())
				.as("subscribing twice still adds one row").isEqualTo(baseline + 1);

		JsonNode row = null;
		for (JsonNode candidate : watched.path("content")) {
			if (key.equals(candidate.path("readableId").asText())) {
				row = candidate;
				break;
			}
		}
		assertThat(row).as("the issue just watched is on the page").isNotNull();
		// The client renders the toggle and the watcher count straight off the issue.
		assertThat(row.path("watcherIds").isArray()).isTrue();
		assertThat(row.path("watcherIds")).as("at least the subscriber").isNotEmpty();

		assertThat(delete("/api/v1/issues/" + key + "/watch", token).statusCode())
				.as("unsubscribe").isEqualTo(204);
		assertThat(delete("/api/v1/issues/" + key + "/watch", token).statusCode())
				.as("unsubscribing again is a no-op").isEqualTo(204);

		JsonNode after = getOk("/api/v1/me/watched?page=0&size=50", token);
		assertThat(after.path("totalElements").asInt())
				.as("back where we started").isEqualTo(baseline);
		for (JsonNode candidate : after.path("content")) {
			assertThat(candidate.path("readableId").asText())
					.as("the unsubscribed issue is gone").isNotEqualTo(key);
		}
	}

	@Test
	@DisplayName("sso exchange is public and rejects an invalid code (400)")
	void ssoExchangeRejectsInvalidCode() {
		// Unauthenticated by design (the user is mid-login) — must not 401/403,
		// but an unknown/expired/used code is a 400.
		HttpResponse<String> res = postJson("/api/v1/auth/sso/exchange",
				"{\"code\":\"definitely-not-a-real-code\"}", null);
		assertThat(res.statusCode()).isEqualTo(400);
	}
}
