package com.ahmadre.hinata.mcp;

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
 * Security invariants of the MCP endpoint + Personal Access Tokens, exercised
 * black-box over real HTTP against a throwaway Mongo container (nothing mocked).
 *
 * <p>These are the load-bearing guarantees of the design:
 * <ul>
 *   <li>a PAT is returned in plaintext exactly once and never re-exposed;</li>
 *   <li>a PAT authenticates on {@code /mcp} but is <em>rejected</em> on the
 *       regular REST API — so a scoped token can never become full API access;</li>
 *   <li>the {@code /mcp} endpoint refuses anonymous requests.</li>
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
class McpSecurityIntegrationTest {

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

	// --- helpers -----------------------------------------------------------

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

	private HttpResponse<String> postJson(String path, String json, String token, String accept) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (accept != null) {
			b.header("Accept", accept);
		}
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return send(b.build());
	}

	private String login() {
		HttpResponse<String> res = postJson("/api/v1/auth/login",
				"{\"identifier\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}",
				null, null);
		assertThat(res.statusCode()).as("login status").isEqualTo(200);
		String token = parse(res.body()).path("accessToken").asText(null);
		assertThat(token).as("access token").isNotBlank();
		return token;
	}

	/** Creates a PAT with the given scopes and returns the one-time plaintext. */
	private String createPat(String jwt, String name, String... scopes) {
		StringBuilder scopeJson = new StringBuilder("[");
		for (int i = 0; i < scopes.length; i++) {
			if (i > 0) {
				scopeJson.append(',');
			}
			scopeJson.append('"').append(scopes[i]).append('"');
		}
		scopeJson.append(']');
		HttpResponse<String> res = postJson("/api/v1/me/pats",
				"{\"name\":\"" + name + "\",\"scopes\":" + scopeJson + "}", jwt, null);
		assertThat(res.statusCode()).as("create PAT").isEqualTo(201);
		JsonNode body = parse(res.body());
		String plaintext = body.path("token").asText(null);
		assertThat(plaintext).as("plaintext token").startsWith("hn_pat_");
		// The safe metadata view must never echo the hash or the plaintext.
		assertThat(body.path("meta").toString()).doesNotContain(plaintext);
		assertThat(body.path("meta").toString()).doesNotContain("tokenHash");
		return plaintext;
	}

	/**
	 * POSTs a JSON-RPC message to /mcp over the streamable-HTTP transport (Accept
	 * lists both media types, as the spec requires). The body is streamed
	 * ({@code ofInputStream}) so the caller can read the first event and close,
	 * rather than blocking until the SSE stream ends.
	 */
	private HttpResponse<java.io.InputStream> mcp(String rpc, String pat, String sessionId) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url("/mcp"))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("Authorization", "Bearer " + pat)
				.POST(HttpRequest.BodyPublishers.ofString(rpc));
		if (sessionId != null) {
			b.header("Mcp-Session-Id", sessionId);
		}
		try {
			return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception e) {
			throw new AssertionError("MCP request failed", e);
		}
	}

	/** Reads the first JSON-RPC payload from a (possibly SSE) MCP response, then closes. */
	private JsonNode mcpBody(HttpResponse<java.io.InputStream> res) {
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(res.body(), java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (line.startsWith("data:")) {
					return parse(line.substring(5).strip());
				}
				if (line.startsWith("{")) {
					return parse(line);
				}
			}
			throw new AssertionError("No JSON-RPC payload in MCP response");
		}
		catch (java.io.IOException e) {
			throw new AssertionError("Reading MCP response failed", e);
		}
	}

	// --- tests -------------------------------------------------------------

	@Test
	@DisplayName("a PAT is created with a one-time plaintext and listed without secrets")
	void patCreatedAndListedSafely() {
		String jwt = login();
		String pat = createPat(jwt, "Claude Desktop", "issues:read", "search:read");

		HttpResponse<String> list = get("/api/v1/me/pats", jwt);
		assertThat(list.statusCode()).isEqualTo(200);
		String listBody = list.body();
		assertThat(listBody).contains("Claude Desktop");
		// Neither the plaintext nor the hash is ever returned to a client.
		assertThat(listBody).doesNotContain(pat);
		assertThat(listBody).doesNotContain("tokenHash");
	}

	@Test
	@DisplayName("a PAT is rejected on the regular REST API (confined to /mcp)")
	void patRejectedOnRestApi() {
		String jwt = login();
		String pat = createPat(jwt, "Cursor", "issues:read", "projects:read");

		// Same token that works on /mcp must NOT authenticate a normal REST call,
		// otherwise a scoped PAT would become unrestricted API access.
		assertThat(get("/api/v1/projects", pat).statusCode()).isEqualTo(401);
		assertThat(get("/api/v1/issues", pat).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("the /mcp endpoint refuses anonymous requests")
	void mcpRejectsAnonymous() {
		String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
		HttpResponse<String> res = postJson("/mcp", rpc, null,
				"application/json, text/event-stream");
		assertThat(res.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("a valid PAT passes authentication on /mcp")
	void mcpAcceptsValidPat() {
		String jwt = login();
		String pat = createPat(jwt, "AI client", "issues:read");
		String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
		HttpResponse<String> res = postJson("/mcp", rpc, pat,
				"application/json, text/event-stream");
		// Whatever the transport does with the handshake, auth must have passed:
		// a rejected token would surface as 401/403.
		assertThat(res.statusCode()).isNotIn(401, 403);
	}

	@Test
	@DisplayName("a revoked PAT no longer authenticates on /mcp")
	void revokedPatRejected() {
		String jwt = login();
		String pat = createPat(jwt, "throwaway", "issues:read");
		String id = parse(get("/api/v1/me/pats", jwt).body()).get(0).path("id").asText();

		HttpResponse<String> del = send(HttpRequest.newBuilder(url("/api/v1/me/pats/" + id))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + jwt).DELETE().build());
		assertThat(del.statusCode()).isEqualTo(204);

		String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";
		HttpResponse<String> res = postJson("/mcp", rpc, pat,
				"application/json, text/event-stream");
		assertThat(res.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("MCP exposes the hinata tools and executes one as the token owner")
	void mcpListsAndCallsTools() {
		String jwt = login();
		String pat = createPat(jwt, "AI client", "issues:read");

		// 1. initialize — establishes the streamable-HTTP session.
		String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"itest\",\"version\":\"1.0\"}}}";
		HttpResponse<java.io.InputStream> initRes = mcp(init, pat, null);
		assertThat(initRes.statusCode()).as("initialize").isEqualTo(200);
		String sessionId = initRes.headers().firstValue("Mcp-Session-Id").orElse(null);
		assertThat(mcpBody(initRes).path("result").path("serverInfo").path("name").asText())
				.isEqualTo("hinata");

		// 2. tools/list — all registered @McpTool names must appear.
		String listRpc = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
		JsonNode tools = mcpBody(mcp(listRpc, pat, sessionId)).path("result").path("tools");
		assertThat(tools.isArray()).isTrue();
		String names = tools.toString();
		assertThat(names)
				.contains("search_issues").contains("get_issue").contains("list_projects")
				.contains("create_issue").contains("add_comment").contains("log_work");

		// 3. tools/call search_issues — proves currentUser.require() resolves under
		//    SYNC and the call runs as the token owner (returns their visible data,
		//    not an auth error).
		String callRpc = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"search_issues\",\"arguments\":{\"size\":5}}}";
		JsonNode result = mcpBody(mcp(callRpc, pat, sessionId)).path("result");
		assertThat(result.path("isError").asBoolean(false)).as("tool call not an error").isFalse();
	}
}
