package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.storage.AttachmentContentService;
import com.ahmadre.hinata.storage.ImageOps;
import com.ahmadre.hinata.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The acceptance criterion of HIN-53, exercised the way a client actually meets
 * it: over the real {@code /mcp} Streamable-HTTP transport, authenticated with a
 * real Personal Access Token, against a real issue in a real database — a
 * screenshot attached to a ticket comes back as something an agent can look at,
 * with no human copying it into a chat.
 *
 * <p>The unit tests around {@link AttachmentContentService} prove the bytes are
 * bounded and re-encoded. What only this level can prove is the half in
 * between: that a {@code CallToolResult} carrying an {@link
 * io.modelcontextprotocol.spec.McpSchema.ImageContent} is serialized by the MCP
 * framework into the {@code {"type":"image","data":…,"mimeType":…}} a client
 * knows how to render, and that a {@code ReadResourceResult} carrying a blob
 * survives the same trip.
 *
 * <p>Object storage is the one thing stubbed. It is not what is under test here,
 * and standing up a bucket would only prove that MinIO stores what it is given.
 * Everything the change owns — authorization, the attachment lookup, the render,
 * the wire encoding — is the real implementation.
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
class McpAttachmentIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ADMIN_USER = "admin";
	private static final String ADMIN_PASS = "hinata-demo-2026";

	/** The key the stubbed store answers for — and the string no response may contain. */
	private static final String OBJECT_KEY = "attachment-object-key-that-must-never-be-returned";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	@LocalServerPort
	private int port;

	@MockitoBean
	private StorageService storage;

	@Autowired
	private com.ahmadre.hinata.storage.AttachmentStore attachments;

	private String jwt;
	private String pat;
	private String sessionId;

	@BeforeEach
	void setUp() {
		when(storage.isConfigured()).thenReturn(true);
		jwt = login();
		pat = createPat("mcp attachment client", "issues:read");
		sessionId = initialize();
	}

	// --- the tests ---------------------------------------------------------

	@Test
	@DisplayName("a screenshot on an issue arrives as image content an agent can look at")
	void aScreenshotComesBackAsAnImage() throws Exception {
		Attached attached = attach("screenshot.png", "image/png", png(2400, 1200));

		JsonNode result = callGetAttachment(attached, null);

		assertThat(result.path("isError").asBoolean(false)).as("not an error").isFalse();
		JsonNode image = contentOfType(result, "image");
		assertThat(image).as("image content present").isNotNull();
		assertThat(image.path("mimeType").asText()).isEqualTo("image/jpeg");
		byte[] bytes = Base64.getDecoder().decode(image.path("data").asText());
		BufferedImage decoded = ImageOps.read(bytes);
		assertThat(decoded).as("the payload is a real, decodable picture").isNotNull();
		// Downscaled to the server default, never the 2400px original.
		assertThat(decoded.getWidth()).isEqualTo(1600);
		assertThat(decoded.getHeight()).isEqualTo(800);
		// And the accompanying text says what it is, so a model cannot mistake a
		// reduced copy for the full-resolution file.
		assertThat(textOf(result)).contains("1600×800").contains("2400×1200");
	}

	@Test
	@DisplayName("a caller may ask for a narrower image and gets one")
	void theCallerCanAskForLess() throws Exception {
		Attached attached = attach("wide.png", "image/png", png(2400, 1200));

		JsonNode result = callGetAttachment(attached, 640);

		byte[] bytes = Base64.getDecoder().decode(contentOfType(result, "image").path("data").asText());
		assertThat(ImageOps.read(bytes).getWidth()).isEqualTo(640);
	}

	@Test
	@DisplayName("a text attachment arrives as text")
	void aTextFileComesBackAsText() throws Exception {
		Attached attached = attach("notes.txt", "text/plain",
				"the layout breaks below 400px".getBytes(StandardCharsets.UTF_8));

		JsonNode result = callGetAttachment(attached, null);

		assertThat(textOf(result)).contains("the layout breaks below 400px");
		assertThat(contentOfType(result, "image")).as("no image for a text file").isNull();
	}

	@Test
	@DisplayName("an unshowable type explains itself rather than failing the call")
	void anArchiveExplainsItself() throws Exception {
		Attached attached = attach("logs.zip", "application/zip", new byte[] { 0x50, 0x4b, 3, 4 });

		JsonNode result = callGetAttachment(attached, null);

		assertThat(result.path("isError").asBoolean(false)).as("a fact, not an error").isFalse();
		assertThat(textOf(result)).contains("No content returned");
	}

	@Test
	@DisplayName("an attachment id from another issue is refused")
	void anAttachmentIdFromAnotherIssueIsRefused() throws Exception {
		Attached mine = attach("mine.png", "image/png", png(400, 400));
		Attached theirs = attach("theirs.png", "image/png", png(400, 400));

		// Both issues are readable by this caller — only the pairing is wrong, which
		// is exactly the case a per-attachment lookup would have let through.
		JsonNode result = call("get_attachment",
				"{\"idOrReadableId\":\"" + mine.issueKey + "\",\"attachmentId\":\"" + theirs.attachmentId + "\"}");

		assertThat(result.path("isError").asBoolean(false)).as("refused").isTrue();
	}

	@Test
	@DisplayName("the internal storage key is in no response, on either surface")
	void theStorageKeyNeverTravels() throws Exception {
		Attached attached = attach("screenshot.png", "image/png", png(800, 600));

		String toolCall = raw("tools/call", "{\"name\":\"get_attachment\",\"arguments\":{"
				+ "\"idOrReadableId\":\"" + attached.issueKey + "\","
				+ "\"attachmentId\":\"" + attached.attachmentId + "\"}}");
		String listing = raw("tools/call", "{\"name\":\"list_attachments\",\"arguments\":{"
				+ "\"idOrReadableId\":\"" + attached.issueKey + "\"}}");
		String resource = raw("resources/read", "{\"uri\":\"hinata://issue/" + attached.issueKey
				+ "/attachment/" + attached.attachmentId + "\"}");

		assertThat(toolCall).doesNotContain(OBJECT_KEY);
		assertThat(listing).doesNotContain(OBJECT_KEY);
		assertThat(resource).doesNotContain(OBJECT_KEY);
	}

	@Test
	@DisplayName("the resource form returns the picture as a binary blob")
	void theResourceReturnsABlob() throws Exception {
		Attached attached = attach("screenshot.png", "image/png", png(1000, 500));

		JsonNode result = rpc("resources/read", "{\"uri\":\"hinata://issue/" + attached.issueKey
				+ "/attachment/" + attached.attachmentId + "\"}").path("result");

		JsonNode blob = null;
		JsonNode prose = null;
		for (JsonNode contents : result.path("contents")) {
			if (contents.hasNonNull("blob")) blob = contents;
			if (contents.hasNonNull("text")) prose = contents;
		}
		assertThat(blob).as("a blob, not prose about a picture").isNotNull();
		assertThat(blob.path("mimeType").asText()).isEqualTo("image/jpeg");
		assertThat(ImageOps.read(Base64.getDecoder().decode(blob.path("blob").asText()))).isNotNull();
		// The framing travels with it: a screenshot can contain writing, and
		// writing a model reads is writing that can try to instruct it.
		assertThat(prose).as("the untrusted-content notice").isNotNull();
		assertThat(prose.path("text").asText()).contains("untrusted");
	}

	@Test
	@DisplayName("a token without issues:read is refused on both surfaces")
	void anUnscopedTokenIsRefused() throws Exception {
		Attached attached = attach("screenshot.png", "image/png", png(400, 400));
		// A token that can read notifications and nothing else.
		pat = createPat("wrong scope", "notifications:read");
		sessionId = initialize();

		JsonNode tool = call("get_attachment", "{\"idOrReadableId\":\"" + attached.issueKey
				+ "\",\"attachmentId\":\"" + attached.attachmentId + "\"}");
		assertThat(tool.path("isError").asBoolean(false)).as("tool refused").isTrue();

		JsonNode resource = rpc("resources/read", "{\"uri\":\"hinata://issue/" + attached.issueKey
				+ "/attachment/" + attached.attachmentId + "\"}");
		assertThat(resource.has("error") || resource.path("result").path("isError").asBoolean(false))
				.as("resource refused").isTrue();
	}

	// --- fixture -----------------------------------------------------------

	/** An issue with one attachment on it, and the ids needed to ask for it. */
	private record Attached(String issueKey, String attachmentId) {
	}

	/**
	 * Creates an issue and hangs [data] off it as an attachment, stubbing the
	 * object store to answer with those exact bytes for this attachment's key.
	 */
	private Attached attach(String fileName, String contentType, byte[] data) {
		String projectId = firstProjectId();
		HttpResponse<String> created = postJson("/api/v1/issues",
				"{\"projectId\":\"" + projectId + "\",\"title\":\"attachment fixture\"}", jwt);
		assertThat(created.statusCode()).as("create issue").isEqualTo(201);
		JsonNode issue = parse(created.body());
		String attachmentId = UUID.randomUUID().toString();
		String objectKey = OBJECT_KEY + "/" + attachmentId;
		attachments.add(issue.path("id").asText(), Issue.Attachment.builder()
				.id(attachmentId)
				.fileName(fileName)
				.contentType(contentType)
				.size(data.length)
				.objectKey(objectKey)
				.uploaderId("admin")
				.uploadedAt(Instant.now())
				.build());
		when(storage.getObject(objectKey))
				.thenReturn(Optional.of(new StorageService.StoredObject(data, contentType)));
		return new Attached(issue.path("readableId").asText(), attachmentId);
	}

	private String firstProjectId() {
		HttpResponse<String> res = get("/api/v1/projects", jwt);
		assertThat(res.statusCode()).as("list projects").isEqualTo(200);
		JsonNode projects = parse(res.body());
		assertThat(projects).as("the demo seed produced a project").isNotEmpty();
		return projects.get(0).path("id").asText();
	}

	private static byte[] png(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(30, 120, 200));
		g.fillRect(0, 0, width, height);
		// Some contrast, so the encoder has something to do and the assertion on
		// the decoded size is not measuring a single flat colour.
		g.setColor(Color.WHITE);
		g.fillRect(width / 4, height / 4, width / 2, height / 2);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	// --- MCP plumbing ------------------------------------------------------

	private JsonNode callGetAttachment(Attached attached, Integer maxWidth) {
		String width = maxWidth == null ? "" : ",\"maxWidth\":" + maxWidth;
		return call("get_attachment", "{\"idOrReadableId\":\"" + attached.issueKey
				+ "\",\"attachmentId\":\"" + attached.attachmentId + "\"" + width + "}");
	}

	private JsonNode call(String tool, String arguments) {
		return rpc("tools/call",
				"{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}").path("result");
	}

	private JsonNode rpc(String method, String params) {
		return parse(raw(method, params));
	}

	/** The raw JSON-RPC payload, for assertions about what a response must not contain. */
	private String raw(String method, String params) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":"
				+ params + "}";
		return payload(mcp(body, sessionId));
	}

	private String initialize() {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"itest\",\"version\":\"1.0\"}}}";
		HttpResponse<InputStream> response = mcp(body, null);
		assertThat(response.statusCode()).as("initialize").isEqualTo(200);
		String id = response.headers().firstValue("Mcp-Session-Id").orElse(null);
		payload(response);
		return id;
	}

	/**
	 * POSTs one JSON-RPC message to {@code /mcp}. The body is taken as a stream,
	 * not a String: the streamable-HTTP transport answers with an event stream
	 * that stays open, so reading it to completion never completes.
	 */
	private HttpResponse<InputStream> mcp(String body, String session) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(url("/mcp"))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("Authorization", "Bearer " + pat)
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (session != null) {
			builder.header("Mcp-Session-Id", session);
		}
		try {
			return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception e) {
			throw new AssertionError("MCP request failed", e);
		}
	}

	/** The first JSON-RPC payload of a (possibly SSE) MCP response, then closes. */
	private static String payload(HttpResponse<InputStream> response) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
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

	/** The first content item of [type] in a tool result, or null. */
	private static JsonNode contentOfType(JsonNode result, String type) {
		for (JsonNode content : result.path("content")) {
			if (type.equals(content.path("type").asText())) {
				return content;
			}
		}
		return null;
	}

	/** Every text content item of a tool result, joined. */
	private static String textOf(JsonNode result) {
		StringBuilder text = new StringBuilder();
		for (JsonNode content : result.path("content")) {
			if ("text".equals(content.path("type").asText())) {
				text.append(content.path("text").asText()).append('\n');
			}
		}
		return text.toString();
	}

	// --- HTTP plumbing -----------------------------------------------------

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
		return send(HttpRequest.newBuilder(url(path)).GET()
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + token)
				.build());
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

	private String login() {
		HttpResponse<String> res = postJson("/api/v1/auth/login",
				"{\"identifier\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}", null);
		assertThat(res.statusCode()).as("login").isEqualTo(200);
		return parse(res.body()).path("accessToken").asText(null);
	}

	private String createPat(String name, String... scopes) {
		StringBuilder scopeJson = new StringBuilder("[");
		for (int i = 0; i < scopes.length; i++) {
			if (i > 0) {
				scopeJson.append(',');
			}
			scopeJson.append('"').append(scopes[i]).append('"');
		}
		scopeJson.append(']');
		HttpResponse<String> res = postJson("/api/v1/me/pats",
				"{\"name\":\"" + name + "\",\"scopes\":" + scopeJson + "}", jwt);
		assertThat(res.statusCode()).as("create PAT").isEqualTo(201);
		return parse(res.body()).path("token").asText(null);
	}
}
