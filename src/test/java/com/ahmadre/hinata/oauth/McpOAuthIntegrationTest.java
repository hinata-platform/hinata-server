package com.ahmadre.hinata.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * End-to-end exercise of the MCP OAuth 2.1 authorization server (Phase 2) over
 * real HTTP: dynamic client registration → authorize (→ consent) → token
 * (authorization-code + PKCE) → calling {@code /mcp} with the issued token →
 * refresh. Plus the security guarantees: PKCE is enforced, codes are single-use,
 * discovery metadata is published, and a 401 advertises the resource metadata.
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
class McpOAuthIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";
	private static final String REDIRECT_URI = "https://example.com/callback";

	// Never follow redirects — the authorize endpoint's 302 is what we inspect.
	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
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
			throw new AssertionError("Not JSON: " + body, e);
		}
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception e) {
			throw new AssertionError("HTTP failed: " + request.uri(), e);
		}
	}

	private HttpResponse<String> get(String path, String bearer) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET().timeout(Duration.ofSeconds(15));
		if (bearer != null) {
			b.header("Authorization", "Bearer " + bearer);
		}
		return send(b.build());
	}

	private HttpResponse<String> postJson(String path, String json, String bearer) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (bearer != null) {
			b.header("Authorization", "Bearer " + bearer);
		}
		return send(b.build());
	}

	private HttpResponse<String> postForm(String path, Map<String, String> form) {
		StringBuilder body = new StringBuilder();
		form.forEach((k, v) -> {
			if (body.length() > 0) {
				body.append('&');
			}
			body.append(enc(k)).append('=').append(enc(v));
		});
		return send(HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	private static String enc(String v) {
		return URLEncoder.encode(v, StandardCharsets.UTF_8);
	}

	private String login() {
		JsonNode body = parse(postJson("/api/v1/auth/login",
				"{\"identifier\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}", null).body());
		return body.path("accessToken").asText();
	}

	private static String queryParam(String url, String key) {
		String q = URI.create(url).getQuery();
		if (q == null) {
			return null;
		}
		for (String pair : q.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(key)) {
				return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	/** Registers a public (PKCE) client and returns its client_id. */
	private String registerClient() {
		String body = "{\"client_name\":\"Test AI\",\"redirect_uris\":[\"" + REDIRECT_URI
				+ "\"],\"token_endpoint_auth_method\":\"none\"}";
		HttpResponse<String> res = postJson("/oauth/register", body, null);
		assertThat(res.statusCode()).as("register").isEqualTo(201);
		return parse(res.body()).path("client_id").asText();
	}

	// --- tests -------------------------------------------------------------

	@Test
	@DisplayName("discovery metadata (RFC 9728 + RFC 8414) is published")
	void discoveryMetadata() {
		// Root document (served by Spring Security's built-in filter, customized).
		JsonNode prm = parse(get("/.well-known/oauth-protected-resource", null).body());
		assertThat(prm.path("resource").asText()).endsWith("/mcp");
		assertThat(prm.path("authorization_servers").isArray()).isTrue();
		assertThat(prm.path("authorization_servers").toString()).contains("http");
		// Path-based variant (served by our controller) for the /mcp resource.
		JsonNode prmPath = parse(get("/.well-known/oauth-protected-resource/mcp", null).body());
		assertThat(prmPath.path("resource").asText()).endsWith("/mcp");
		assertThat(prmPath.path("authorization_servers").isArray()).isTrue();

		JsonNode asm = parse(get("/.well-known/oauth-authorization-server", null).body());
		assertThat(asm.path("authorization_endpoint").asText()).endsWith("/oauth/authorize");
		assertThat(asm.path("token_endpoint").asText()).endsWith("/oauth/token");
		assertThat(asm.path("registration_endpoint").asText()).endsWith("/oauth/register");
		assertThat(asm.path("code_challenge_methods_supported").toString()).contains("S256");
	}

	@Test
	@DisplayName("a 401 on /mcp advertises the resource metadata (WWW-Authenticate)")
	void unauthorizedAdvertisesMetadata() {
		HttpResponse<String> res = get("/mcp", null);
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(res.headers().firstValue("WWW-Authenticate").orElse(""))
				.contains("resource_metadata").contains("oauth-protected-resource");
	}

	@Test
	@DisplayName("full authorization-code + PKCE flow yields a token that works on /mcp")
	void fullAuthorizationCodeFlow() throws Exception {
		String clientId = registerClient();

		// PKCE
		byte[] verifierBytes = new byte[32];
		new SecureRandom().nextBytes(verifierBytes);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

		// authorize → 302 to the web consent page carrying request_id
		String authorize = "/oauth/authorize?response_type=code&client_id=" + enc(clientId)
				+ "&redirect_uri=" + enc(REDIRECT_URI) + "&scope=" + enc("issues:read")
				+ "&state=xyz&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
		HttpResponse<String> authRes = get(authorize, null);
		assertThat(authRes.statusCode()).isEqualTo(302);
		String consentLocation = authRes.headers().firstValue("Location").orElseThrow();
		assertThat(consentLocation).contains("/oauth-consent");
		String requestId = queryParam(consentLocation, "request_id");
		assertThat(requestId).isNotBlank();

		// consent (authenticated) — info then approve
		String jwt = login();
		JsonNode info = parse(get("/api/v1/oauth/consent/" + requestId, jwt).body());
		assertThat(info.path("clientName").asText()).isEqualTo("Test AI");
		assertThat(info.path("scopes").toString()).contains("issues:read");

		HttpResponse<String> decideRes = postJson("/api/v1/oauth/consent",
				"{\"requestId\":\"" + requestId + "\",\"approved\":true,\"grantedScopes\":[\"issues:read\"]}", jwt);
		assertThat(decideRes.statusCode()).isEqualTo(200);
		String clientRedirect = parse(decideRes.body()).path("redirectUri").asText();
		assertThat(clientRedirect).startsWith(REDIRECT_URI);
		assertThat(queryParam(clientRedirect, "state")).isEqualTo("xyz");
		String code = queryParam(clientRedirect, "code");
		assertThat(code).isNotBlank();

		// token exchange (authorization_code + PKCE)
		HttpResponse<String> tokenRes = postForm("/oauth/token", form(
				"grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
				"code_verifier", verifier, "client_id", clientId));
		assertThat(tokenRes.statusCode()).as("token").isEqualTo(200);
		JsonNode token = parse(tokenRes.body());
		String accessToken = token.path("access_token").asText();
		String refreshToken = token.path("refresh_token").asText();
		assertThat(accessToken).isNotBlank();
		assertThat(token.path("token_type").asText()).isEqualTo("Bearer");
		assertThat(token.path("scope").asText()).isEqualTo("issues:read");

		// use the OAuth access token on /mcp — the tools run as the token owner
		String sessionId = mcpInitialize(accessToken);
		JsonNode tools = mcpBody(mcpCall(accessToken, sessionId,
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
				.path("result").path("tools");
		assertThat(tools.toString()).contains("search_issues");

		JsonNode listResult = mcpBody(mcpCall(accessToken, sessionId,
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"list_my_issues\",\"arguments\":{\"size\":3}}}")).path("result");
		assertThat(listResult.path("isError").asBoolean(false)).as("read within scope").isFalse();

		// a write tool is refused — the token only holds issues:read
		JsonNode writeResult = mcpBody(mcpCall(accessToken, sessionId,
				"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"add_comment\",\"arguments\":{\"idOrReadableId\":\"HIN-1\",\"text\":\"x\"}}}"))
				.path("result");
		assertThat(writeResult.path("isError").asBoolean(false)).as("write out of scope is rejected").isTrue();

		// refresh yields a fresh access token
		HttpResponse<String> refreshRes = postForm("/oauth/token", form(
				"grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", clientId));
		assertThat(refreshRes.statusCode()).as("refresh").isEqualTo(200);
		assertThat(parse(refreshRes.body()).path("access_token").asText()).isNotBlank();

		// the authorization code is single-use — a replay is rejected
		HttpResponse<String> replay = postForm("/oauth/token", form(
				"grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
				"code_verifier", verifier, "client_id", clientId));
		assertThat(replay.statusCode()).isEqualTo(400);
		assertThat(parse(replay.body()).path("error").asText()).isEqualTo("invalid_grant");
	}

	@Test
	@DisplayName("a wrong PKCE verifier is rejected at the token endpoint")
	void wrongPkceRejected() throws Exception {
		String clientId = registerClient();
		byte[] v = new byte[32];
		new SecureRandom().nextBytes(v);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		String authorize = "/oauth/authorize?response_type=code&client_id=" + enc(clientId)
				+ "&redirect_uri=" + enc(REDIRECT_URI) + "&scope=" + enc("issues:read")
				+ "&state=s&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
		String requestId = queryParam(get(authorize, null).headers().firstValue("Location").orElseThrow(), "request_id");
		String jwt = login();
		String clientRedirect = parse(postJson("/api/v1/oauth/consent",
				"{\"requestId\":\"" + requestId + "\",\"approved\":true,\"grantedScopes\":[\"issues:read\"]}", jwt)
				.body()).path("redirectUri").asText();
		String code = queryParam(clientRedirect, "code");

		HttpResponse<String> tokenRes = postForm("/oauth/token", form(
				"grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
				"code_verifier", "the-wrong-verifier", "client_id", clientId));
		assertThat(tokenRes.statusCode()).isEqualTo(400);
		assertThat(parse(tokenRes.body()).path("error").asText()).isEqualTo("invalid_grant");
	}

	// --- MCP streamable-HTTP mini client -----------------------------------

	private String mcpInitialize(String bearer) {
		HttpResponse<String> res = send(HttpRequest.newBuilder(url("/mcp")).timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
						+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
						+ "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}")).build());
		assertThat(res.statusCode()).as("mcp initialize").isEqualTo(200);
		return res.headers().firstValue("Mcp-Session-Id").orElse(null);
	}

	private HttpResponse<java.io.InputStream> mcpCall(String bearer, String sessionId, String rpc) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url("/mcp")).timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(rpc));
		if (sessionId != null) {
			b.header("Mcp-Session-Id", sessionId);
		}
		try {
			return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception e) {
			throw new AssertionError("MCP call failed", e);
		}
	}

	private JsonNode mcpBody(HttpResponse<java.io.InputStream> res) {
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
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

	private static Map<String, String> form(String... kv) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			map.put(kv[i], kv[i + 1]);
		}
		return map;
	}
}
