package com.ahmadre.hinata.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A minimal MCP client over the real Streamable-HTTP transport, for integration tests: signs in
 * through the REST API, mints a Personal Access Token with the scopes asked for, and speaks
 * JSON-RPC to {@code /mcp} with it. One instance per token; each opens its own session.
 */
final class McpTestClient {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final int port;
	private final String token;
	private final String sessionId;

	private McpTestClient(int port, String token) {
		this.port = port;
		this.token = token;
		this.sessionId = initialize();
	}

	/** Signs in as [username] and opens a session with a new token holding [scopes]. */
	static McpTestClient withScopes(int port, String username, String password, String... scopes) {
		String jwt = login(port, username, password);
		StringBuilder list = new StringBuilder("[");
		for (int i = 0; i < scopes.length; i++) {
			list.append(i > 0 ? "," : "").append('"').append(scopes[i]).append('"');
		}
		HttpResponse<String> res = post(port, "/api/v1/me/pats",
				"{\"name\":\"itest " + System.nanoTime() + "\",\"scopes\":" + list.append(']') + "}", jwt);
		assertThat(res.statusCode()).as("create PAT").isEqualTo(201);
		return new McpTestClient(port, parse(res.body()).path("token").asText());
	}

	static String login(int port, String username, String password) {
		HttpResponse<String> res = post(port, "/api/v1/auth/login",
				"{\"identifier\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
		assertThat(res.statusCode()).as("login").isEqualTo(200);
		return parse(res.body()).path("accessToken").asText();
	}

	/** {@code tools/list}: every tool as the server advertises it. */
	JsonNode tools() {
		return rpc("tools/list", "{}").path("result").path("tools");
	}

	/** {@code tools/call}; the {@code result} member, which carries {@code isError} for a refusal. */
	JsonNode call(String tool, String arguments) {
		return rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}").path("result");
	}

	/** The concatenated text content of a tool result. */
	static String text(JsonNode result) {
		StringBuilder text = new StringBuilder();
		for (JsonNode content : result.path("content")) {
			text.append(content.path("text").asText(""));
		}
		return text.toString();
	}

	/** The whole JSON-RPC answer to a {@code resources/read} — a refusal arrives as {@code error}. */
	JsonNode readResource(String uri) {
		return rpc("resources/read", "{\"uri\":\"" + uri + "\"}");
	}

	JsonNode rpc(String method, String params) {
		return parse(payload(send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":"
				+ params + "}", sessionId)));
	}

	private String initialize() {
		HttpResponse<InputStream> response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"itest\",\"version\":\"1.0\"}}}", null);
		assertThat(response.statusCode()).as("initialize").isEqualTo(200);
		String id = response.headers().firstValue("Mcp-Session-Id").orElse(null);
		payload(response);
		return id;
	}

	/** Taken as a stream: the transport answers with an event stream that stays open. */
	private HttpResponse<InputStream> send(String body, String session) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("Authorization", "Bearer " + token)
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (session != null) {
			builder.header("Mcp-Session-Id", session);
		}
		try {
			return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception e) {
			throw new AssertionError("MCP request failed", e);
		}
	}

	private static String payload(HttpResponse<InputStream> response) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.strip();
				if (trimmed.startsWith("data:")) {
					return trimmed.substring(5).strip();
				}
				if (trimmed.startsWith("{")) {
					return trimmed;
				}
			}
			throw new AssertionError("No JSON-RPC payload in MCP response");
		}
		catch (IOException e) {
			throw new AssertionError("Reading MCP response failed", e);
		}
	}

	private static HttpResponse<String> post(int port, String path, String json, String bearer) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (bearer != null) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		try {
			return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception e) {
			throw new AssertionError("HTTP request failed: " + path, e);
		}
	}

	private static JsonNode parse(String body) {
		try {
			return JSON.readTree(body);
		}
		catch (Exception e) {
			throw new AssertionError("Response was not valid JSON: " + body, e);
		}
	}
}
