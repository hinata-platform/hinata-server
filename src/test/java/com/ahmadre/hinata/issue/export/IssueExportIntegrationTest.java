package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export endpoints over real HTTP.
 *
 * <p>What only this level can prove: that all four routes exist and answer with
 * the content type and the file name a browser will act on, that the project ACL
 * refuses a non-member on every one of them, that the per-caller budget actually
 * stops a loop, and that each download leaves a record. The renderers themselves
 * are tested against their own parsers next door; here the subject is the
 * endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=true",
		"hinata.demo.reset=true",
		// The per-IP filter would throttle the whole suite; the export's own
		// per-caller budget is what is under test and is unaffected by this.
		"hinata.rate-limit.enabled=false",
		// Small enough to reach deliberately, large enough that the other tests
		// in this class never trip it.
		"hinata.rate-limit.exports-per-minute=25",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueExportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String PASSWORD = "hinata-demo-2026";
	/** Seeded with MEMBER only, so a project they were never added to is closed to them. */
	private static final String OUTSIDER = "tomas";
	/**
	 * A second non-member, for the budget test alone. The limiter is a singleton
	 * with a bucket per user, so a test that deliberately empties one caller's
	 * budget would otherwise decide what a later test sees — 429 where it asserted
	 * 403, depending on the order they happened to run in.
	 */
	private static final String LOOPER = "lena";

	private static final List<String> FORMATS = List.of("pdf", "docx", "xlsx", "xml");

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private AuditLogRepository auditLog;

	private String admin;
	private String issueId;

	@BeforeEach
	void setUp() {
		admin = login("admin");
		issueId = createIssue("Einzelnes Issue exportieren und drucken");
	}

	// --- the four routes -----------------------------------------------------

	@Test
	@DisplayName("every format downloads with its own content type and a file name")
	void everyFormatDownloads() {
		for (String format : FORMATS) {
			HttpResponse<byte[]> response = download(issueId, format, admin);

			assertThat(response.statusCode()).as(format).isEqualTo(200);
			assertThat(response.body()).as(format + " body").isNotEmpty();
			String contentType = header(response, "content-type");
			String disposition = header(response, "content-disposition");
			assertThat(disposition).as(format + " disposition")
					.startsWith("attachment;")
					.contains("." + format);
			switch (format) {
				case "pdf" -> {
					assertThat(contentType).isEqualTo("application/pdf");
					assertThat(response.body()).startsWith(new byte[] { '%', 'P', 'D', 'F' });
				}
				case "xml" -> {
					assertThat(contentType).startsWith("application/xml");
					assertThat(new String(response.body())).startsWith("<?xml");
				}
				// A .docx and an .xlsx are ZIP containers; "PK" is the whole of what
				// makes a reader willing to open one at all.
				default -> {
					assertThat(contentType).contains("openxmlformats");
					assertThat(response.body()).startsWith(new byte[] { 'P', 'K' });
				}
			}
		}
	}

	/** An export must never show more than the screen it was started from. */
	@Test
	@DisplayName("a non-member is refused on every format")
	void aNonMemberIsRefusedOnEveryFormat() {
		String outsider = login(OUTSIDER);
		// A project of its own: every demo user is a member of the seeded ones, so
		// an issue there proves nothing about the ACL.
		String closedIssue = createIssue("Not for everyone", createProject("CLSD", "Closed"));

		for (String format : FORMATS) {
			assertThat(download(closedIssue, format, outsider).statusCode())
					.as(format + " for a non-member").isEqualTo(403);
		}
	}

	/**
	 * The title reaches a response header. A newline in it would end that header
	 * and start whatever followed as a second one — so this asserts the header a
	 * real client parsed, not the string a helper produced.
	 */
	@Test
	@DisplayName("a hostile title still produces one valid header")
	void aHostileTitleCannotInjectAHeader() {
		String hostile = createIssue("Grüße\r\nX-Injected: yes\"; filename=\"owned");

		HttpResponse<byte[]> response = download(hostile, "pdf", admin);

		assertThat(response.statusCode()).isEqualTo(200);
		// The header the title tried to forge does not exist, and there is exactly
		// one Content-Disposition rather than a second one the title started.
		assertThat(response.headers().allValues("x-injected")).isEmpty();
		assertThat(response.headers().allValues("content-disposition")).hasSize(1);
		String disposition = header(response, "content-disposition");
		assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
		// The words survive as part of the name — harmless once they are just
		// letters — but the punctuation that made them dangerous does not: exactly
		// two quotes, the pair around the plain filename.
		assertThat(disposition.chars().filter(c -> c == '"').count()).isEqualTo(2);
		assertThat(disposition).doesNotContain(":").doesNotContain(";\"");
		// Umlauts survive, encoded per RFC 5987 by the framework.
		assertThat(disposition).contains("filename*=UTF-8''");
	}

	/**
	 * The leak the ACL on the issue itself does not cover. {@code dependsOnIds} is
	 * stored exactly as it is sent — the update endpoint checks neither its length
	 * nor whether the caller may see anything in it — so a member of one project
	 * can point their own issue at an id from a project that is closed to them.
	 * Resolving that id to a readable key would make the export an oracle for the
	 * keys, and so for the existence and the size, of projects they cannot open.
	 *
	 * <p>Written end to end rather than against the service because the premise is
	 * the half that has to stay true: that the PATCH really does accept the
	 * foreign id. A unit test that stubbed the write would be asserting its own
	 * assumption.
	 */
	@Test
	@DisplayName("an export never resolves an id from a project the caller cannot see")
	void dependsOnDoesNotLeakAForeignKey() {
		String closedIssue = createIssue("Payroll", createProject("PAYR", "Payroll"));
		String closedKey = parse(get("/api/v1/issues/" + closedIssue, admin))
				.path("readableId").asText();
		assertThat(closedKey).startsWith("PAYR-");
		// The outsider's own issue, in a project every demo user belongs to, made to
		// point at the closed one.
		String own = createIssue("Probe");
		HttpResponse<String> patched = patchJson("/api/v1/issues/" + own,
				JSON.createObjectNode().set("dependsOnIds",
						JSON.createArrayNode().add(closedIssue)).toString(), admin);
		assertThat(patched.statusCode()).as("the foreign id is stored, unchecked").isEqualTo(200);

		HttpResponse<byte[]> outsiderResponse = download(own, "xml", login(OUTSIDER));
		// Asserted, not assumed: a refusal here would make every claim below true
		// for the wrong reason.
		assertThat(outsiderResponse.statusCode()).as("the outsider may read the probe")
				.isEqualTo(200);
		String outsiderXml = new String(outsiderResponse.body(), StandardCharsets.UTF_8);
		String adminXml = new String(download(own, "xml", admin).body(), StandardCharsets.UTF_8);

		assertThat(outsiderXml).as("a non-member of PAYR learns nothing about it")
				.contains("<issue version=")
				.doesNotContain(closedKey).doesNotContain("PAYR");
		assertThat(adminXml).as("and the field still works for someone who may see it")
				.contains(closedKey);
	}

	@Test
	@DisplayName("every export leaves a record naming the format")
	void everyExportIsAudited() {
		auditLog.deleteAll();

		download(issueId, "xlsx", admin);

		assertThat(auditLog.findAll())
				.filteredOn(entry -> entry.getAction() == AuditAction.ISSUE_EXPORTED)
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getMetadata()).containsEntry("format", "xlsx");
					assertThat(entry.getMetadata()).containsKey("issue");
					assertThat(entry.getOutcome()).isEqualTo(AuditLog.Outcome.SUCCESS);
				});
	}

	/**
	 * Rendering a document is CPU on a request thread for a request the size of a
	 * URL, so the budget is the thing standing between one loop and the server.
	 */
	@Test
	@DisplayName("the per-caller budget stops a loop")
	void theRateLimitStops() {
		String looper = login(LOOPER);
		String own = createIssue("Rate limit probe", createProject("RLMT", "Rate limit"));
		// The looper cannot read it, so every call is refused on the ACL — but the
		// budget is spent before that, which is the point: probing costs the prober.
		int refusals = 0;
		for (int i = 0; i < 40; i++) {
			if (download(own, "pdf", looper).statusCode() == 429) {
				refusals++;
			}
		}

		assertThat(refusals).as("some calls were answered with 429").isPositive();
	}

	// --- plumbing ------------------------------------------------------------

	private HttpResponse<byte[]> download(String issue, String format, String token) {
		HttpRequest request = HttpRequest.newBuilder(
						url("/api/v1/issues/" + issue + "/export." + format))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + token)
				.GET()
				.build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (Exception e) {
			throw new AssertionError("Export request failed", e);
		}
	}

	private static String header(HttpResponse<byte[]> response, String name) {
		return response.headers().firstValue(name).orElse("");
	}

	/** A project the caller alone belongs to, for the tests about who may not read. */
	private String createProject(String key, String name) {
		HttpResponse<String> created = postJson("/api/v1/projects",
				JSON.createObjectNode().put("key", key).put("name", name).toString(), admin);
		assertThat(created.statusCode()).as("create project").isEqualTo(201);
		return parse(created.body()).path("id").asText();
	}

	private String createIssue(String title) {
		return createIssue(title, parse(get("/api/v1/projects", admin)).get(0).path("id").asText());
	}

	private String createIssue(String title, String projectId) {
		HttpResponse<String> created = postJson("/api/v1/issues",
				JSON.createObjectNode().put("projectId", projectId).put("title", title).toString(),
				admin);
		assertThat(created.statusCode()).as("create issue").isEqualTo(201);
		return parse(created.body()).path("id").asText();
	}

	private String login(String identifier) {
		HttpResponse<String> response = postJson("/api/v1/auth/login",
				JSON.createObjectNode().put("identifier", identifier)
						.put("password", PASSWORD).toString(), null);
		assertThat(response.statusCode()).as("login " + identifier).isEqualTo(200);
		return parse(response.body()).path("accessToken").asText();
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private String get(String path, String token) {
		HttpRequest request = HttpRequest.newBuilder(url(path))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + token)
				.GET().build();
		return send(request).body();
	}

	private HttpResponse<String> patchJson(String path, String json, String token) {
		HttpRequest request = HttpRequest.newBuilder(url(path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json))
				.build();
		return send(request);
	}

	private HttpResponse<String> postJson(String path, String json, String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(url(path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return send(builder.build());
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception e) {
			throw new AssertionError("HTTP request failed: " + request.uri(), e);
		}
	}

	private JsonNode parse(String body) {
		try {
			return JSON.readTree(body);
		}
		catch (Exception e) {
			throw new AssertionError("Response was not JSON: " + body, e);
		}
	}
}
