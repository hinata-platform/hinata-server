package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.richtext.LayoutDocs;
import com.ahmadre.hinata.richtext.LegacyDocs;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An ingested mail with its layout and external images, as clients see it (HIN-181): the legacy
 * projection for published apps, the full document beside it, the reader's trust, the image proxy
 * that only serves an issue's own images to its readers, and an old app's save that must not
 * destroy the layout. Over real HTTP, because the shapes are Jackson's and the doors are filters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.cors.allowed-origins=https://app.example.org"
})
@Testcontainers(disabledWithoutDocker = true)
class ExternalImagesIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String PASSWORD = "Bilder-Test-2026!";
	private static final String HEADER = "https://mail.example.com/img/header_desk.jpg";
	private static final String IN_COMMENT = "https://mail.example.com/img/reply.png";
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
	private static final ObjectMapper JSON = new ObjectMapper();

	@MockitoBean
	private ExternalImageFetcher fetcher;

	@Autowired
	private UserRepository users;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private PasswordEncoder passwords;

	@LocalServerPort
	private int port;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private Issue issue;
	private String layoutDoc;
	private String member;
	private String outsider;

	@BeforeEach
	void seed() throws Exception {
		for (String collection : List.of("users", "sessions", "projects", "issues", "issue_comments")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		User reader = account("lea");
		account("otto");
		Project project = mongo.insert(Project.builder().key("MAIL").name("Postfach").leadId(reader.getId())
				.leadIds(new ArrayList<>(List.of(reader.getId())))
				.memberIds(new ArrayList<>(List.of(reader.getId()))).build());
		layoutDoc = LayoutDocs.doc(LayoutDocs.paragraph("Created from e-mail by shop@kundenservice.vodafone.com"),
				LayoutDocs.columns("280px 1fr", LayoutDocs.item(LayoutDocs.imageParagraph(HEADER)),
						LayoutDocs.item(LayoutDocs.paragraph("Schritt eins"))));
		issue = mongo.insert(Issue.builder().projectId(project.getId()).numberInProject(1).readableId("MAIL-1")
				.title("Deine eSIM").description("Schritt eins").descriptionDoc(layoutDoc)
				.reporterEmail("shop@kundenservice.vodafone.com").senderVerified(true)
				.state(Project.defaultWorkflow().getFirst().getName()).build());
		mongo.insert(IssueComment.builder().issueId(issue.getId()).authorId(reader.getId())
				.type(IssueComment.Type.TEXT).text("Antwort").textDoc(LayoutDocs.doc(LayoutDocs.imageParagraph(IN_COMMENT)))
				.build());
		member = login("lea");
		outsider = login("otto");
		when(fetcher.fetch(anyString())).thenReturn(new ExternalImageFetcher.Image(PNG, "image/png"));
	}

	@Test
	void anIssueGoesOutAsTheLegacyProjectionWithTheFullDocumentBeside() throws Exception {
		JsonNode body = JSON.readTree(get("/api/v1/issues/" + issue.getId(), member).body());

		String legacy = body.path("descriptionDoc").asText();
		assertThat(legacy).doesNotContain("layout-container", "layout-item", "\"image\"");
		assertThat(LayoutDocs.nodes(legacy, "link")).extracting(link -> link.path("url").asText()).containsExactly(HEADER);
		assertThat(body.path("descriptionLayoutDoc").asText()).isEqualTo(layoutDoc);
		assertThat(body.path("externalImageSender").asText()).isEqualTo("kundenservice.vodafone.com");
		assertThat(body.path("externalImagesTrusted").asBoolean()).isFalse();

		JsonNode comment = JSON.readTree(get("/api/v1/issues/" + issue.getId() + "/comments", member).body())
				.path("content").get(0);
		assertThat(comment.path("textDoc").asText()).doesNotContain("\"image\"");
		assertThat(comment.path("textLayoutDoc").asText()).contains(IN_COMMENT);
	}

	@Test
	void anAppAskingForLayoutGetsTheFullDocumentOnlyOnce() throws Exception {
		HttpResponse<String> response = send("GET", "/api/v1/issues/" + issue.getId(), member, null,
				"X-Hinata-Doc", "layout");
		JsonNode body = JSON.readTree(response.body());

		assertThat(body.path("descriptionDoc").asText()).isEqualTo(layoutDoc);
		assertThat(body.has("descriptionLayoutDoc")).isFalse();
		assertThat(response.headers().allValues("Vary")).anySatisfy(vary -> assertThat(vary).contains("X-Hinata-Doc"));
		JsonNode comment = JSON.readTree(send("GET", "/api/v1/issues/" + issue.getId() + "/comments", member, null,
				"X-Hinata-Doc", "layout").body()).path("content").get(0);
		assertThat(comment.path("textDoc").asText()).contains(IN_COMMENT);
		assertThat(comment.has("textLayoutDoc")).isFalse();
	}

	@Test
	void aBrowserMaySendTheDocHeader() throws Exception {
		HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/api/v1/issues/" + issue.getId()))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.header("Origin", "https://app.example.org")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "authorization,x-hinata-doc").build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(preflight.statusCode()).isEqualTo(200);
		assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
				.contains("x-hinata-doc");
	}

	@Test
	void anUnverifiedSenderIsNotOfferedForTrust() throws Exception {
		mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(issue.getId())),
				new org.springframework.data.mongodb.core.query.Update().unset("senderVerified"), Issue.class);

		JsonNode body = JSON.readTree(get("/api/v1/issues/" + issue.getId(), member).body());

		assertThat(body.path("externalImageSender").isNull()).isTrue();
		assertThat(body.path("descriptionLayoutDoc").asText()).contains(HEADER);
	}

	@Test
	void aPublicSuffixCannotBeTrustedButAFreemailDomainCan() throws Exception {
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/co.uk", member).statusCode()).isEqualTo(400);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/com", member).statusCode()).isEqualTo(400);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/github.io", member).statusCode()).isEqualTo(400);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/gmail.com", member).statusCode()).isEqualTo(204);
	}

	@Test
	void anIssueWithoutLayoutOrExternalImagesGoesOutAsBefore() throws Exception {
		String plain = LayoutDocs.doc(LayoutDocs.paragraph("Nur Text"));
		mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(issue.getId())),
				org.springframework.data.mongodb.core.query.Update.update("descriptionDoc", plain), Issue.class);

		JsonNode body = JSON.readTree(get("/api/v1/issues/" + issue.getId(), member).body());

		assertThat(body.path("descriptionDoc").asText()).isEqualTo(plain);
		assertThat(body.has("descriptionLayoutDoc")).as("written only when it differs").isFalse();
		assertThat(body.path("externalImageSender").isNull()).isTrue();
	}

	@Test
	void trustingTheParentDomainLoadsTheImagesAndCanBeTakenBack() throws Exception {
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/Vodafone.COM", member).statusCode()).isEqualTo(204);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/vodafone.com", member).statusCode())
				.as("idempotent").isEqualTo(204);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/b%C3%BCcher.example", member).statusCode()).isEqualTo(204);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/localhost", member).statusCode()).isEqualTo(400);

		JsonNode list = JSON.readTree(get("/api/v1/me/trusted-image-senders?page=0&size=1", member).body());
		assertThat(list.path("total").asInt()).isEqualTo(2);
		assertThat(list.path("items")).hasSize(1);
		assertThat(list.path("items").get(0).path("domain").asText()).as("newest first").isEqualTo("xn--bcher-kva.example");
		assertThat(JSON.readTree(get("/api/v1/issues/" + issue.getId(), member).body())
				.path("externalImagesTrusted").asBoolean()).isTrue();
		assertThat(JSON.readTree(get("/api/v1/issues/" + issue.getId() + "/detail", member).body())
				.path("issue").path("externalImagesTrusted").asBoolean()).isTrue();
		assertThat(JSON.readTree(get("/api/v1/me", member).body()).has("trustedImageSenders"))
				.as("never with the user").isFalse();

		assertThat(send("DELETE", "/api/v1/me/trusted-image-senders/vodafone.com", member).statusCode()).isEqualTo(204);
		assertThat(send("DELETE", "/api/v1/me/trusted-image-senders/vodafone.com", member).statusCode()).isEqualTo(204);
		assertThat(JSON.readTree(get("/api/v1/issues/" + issue.getId(), member).body())
				.path("externalImagesTrusted").asBoolean()).isFalse();
	}

	@Test
	void trustStopsAtFiveHundredDomains() throws Exception {
		User lea = users.findByUsernameIgnoreCase("lea").orElseThrow();
		List<Document> many = new ArrayList<>();
		for (int i = 0; i < 500; i++) many.add(new Document("domain", "d" + i + ".example").append("addedAt", new java.util.Date()));
		mongo.getCollection("users").updateOne(new Document("_id", new org.bson.types.ObjectId(lea.getId())),
				new Document("$set", new Document("trustedImageSenders", many)));

		HttpResponse<String> full = send("PUT", "/api/v1/me/trusted-image-senders/vodafone.com", member);

		assertThat(full.statusCode()).isEqualTo(409);
		assertThat(send("PUT", "/api/v1/me/trusted-image-senders/d7.example", member).statusCode())
				.as("one already trusted is no new one").isEqualTo(204);
	}

	@Test
	void trustGoesOutWhereverOneIssueGoesBackAndIsLeftOutWhereItWasNotWorkedOut() throws Exception {
		send("PUT", "/api/v1/me/trusted-image-senders/vodafone.com", member);

		JsonNode archived = JSON.readTree(send("POST", "/api/v1/issues/" + issue.getId() + "/archive", member).body());
		JsonNode unarchived = JSON.readTree(send("POST", "/api/v1/issues/" + issue.getId() + "/unarchive", member).body());
		HttpResponse<String> cloned = send("POST", "/api/v1/issues/" + issue.getId() + "/clone", member, "{\"title\":\"Kopie\"}");
		JsonNode list = JSON.readTree(get("/api/v1/issues?projectId=" + issue.getProjectId(), member).body());

		assertThat(archived.path("externalImagesTrusted").asBoolean()).isTrue();
		assertThat(unarchived.path("externalImagesTrusted").asBoolean()).isTrue();
		assertThat(cloned.statusCode()).as(cloned.body()).isEqualTo(201);
		assertThat(JSON.readTree(cloned.body()).has("externalImagesTrusted")).as("worked out for the clone").isTrue();
		assertThat(list.path("content").get(0).has("externalImagesTrusted")).as("not worked out for a list").isFalse();
	}

	@Test
	void anOldAppSendingBackTheProjectionLeavesTheLayoutAlone() throws Exception {
		String legacy = LegacyDocs.project(layoutDoc);
		String update = JSON.createObjectNode().put("descriptionDoc", legacy).put("title", "Deine eSIM (gelesen)").toString();

		HttpResponse<String> saved = send("PATCH", "/api/v1/issues/" + issue.getId(), member, update);

		assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
		Issue stored = mongo.findById(issue.getId(), Issue.class);
		assertThat(stored.getTitle()).isEqualTo("Deine eSIM (gelesen)");
		assertThat(stored.getDescriptionDoc()).isEqualTo(layoutDoc);
	}

	@Test
	void aNewAppSavesTheFullDocumentWithItsExternalImages() throws Exception {
		String edited = layoutDoc.replace("Schritt eins", "Schritt eins, erledigt");
		String update = JSON.createObjectNode().put("descriptionDoc", edited).toString();

		assertThat(send("PATCH", "/api/v1/issues/" + issue.getId(), member, update).statusCode()).isEqualTo(200);

		Issue stored = mongo.findById(issue.getId(), Issue.class);
		assertThat(stored.getDescriptionDoc()).contains("layout-container", HEADER, "erledigt");
	}

	@Test
	void theProxyServesAnIssuesOwnImagesToItsReadersOnly() throws Exception {
		String path = "/api/v1/issues/" + issue.getId() + "/external-image?src=";
		HttpResponse<byte[]> image = getBytes(path + encode(HEADER), member);
		assertThat(image.statusCode()).isEqualTo(200);
		assertThat(image.body()).isEqualTo(PNG);
		assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
		assertThat(image.headers().firstValue("Cache-Control").orElse("")).contains("private", "max-age=86400");
		assertThat(getBytes(path + encode(HEADER), member).statusCode()).isEqualTo(200);
		verify(fetcher, times(1)).fetch(HEADER);

		assertThat(getBytes("/api/v1/issues/MAIL-1/external-image?src=" + encode(IN_COMMENT), member).statusCode())
				.as("an image of a comment, by key").isEqualTo(200);
		assertThat(getBytes(path + encode("http://169.254.169.254/latest/meta-data"), member).statusCode())
				.as("no open proxy").isEqualTo(404);
		assertThat(getBytes(path + encode(HEADER), outsider).statusCode()).as("not their issue").isIn(403, 404);
		assertThat(getBytes(path + encode(HEADER), null).statusCode()).isEqualTo(401);
	}

	@Test
	void anImageOfADeletedCommentIsNoLongerServed() throws Exception {
		String path = "/api/v1/issues/" + issue.getId() + "/external-image?src=" + encode(IN_COMMENT);
		assertThat(getBytes(path, member).statusCode()).isEqualTo(200);
		IssueComment comment = mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("issueId").is(issue.getId())), IssueComment.class);

		HttpResponse<String> deleted = send("DELETE", "/api/v1/issues/" + issue.getId() + "/comments/" + comment.getId(),
				member);

		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
		assertThat(getBytes(path, member).statusCode()).isEqualTo(404);
	}

	@Test
	void theProxyAsksNobodyForAnAddressThatIsNotInTheIssue() throws Exception {
		org.mockito.Mockito.reset(fetcher);

		getBytes("/api/v1/issues/" + issue.getId() + "/external-image?src=" + encode("https://evil.example/x.png"), member);

		verifyNoInteractions(fetcher);
	}

	@Test
	void aPersonStoredBeforeTrustExistedStillReadsAndTrustsNobody() {
		User saved = account("alt");
		Document raw = mongo.getCollection("users").find(new Document("_id",
				new org.bson.types.ObjectId(saved.getId()))).first();
		assertThat(raw).doesNotContainKey("trustedImageSenders");

		assertThat(users.findById(saved.getId()).orElseThrow().getTrustedImageSenders()).isEmpty();
	}

	private User account(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.passwordHash(passwords.encode(PASSWORD)).roles(Set.of(Role.MEMBER)).active(true).build());
	}

	private String login(String name) throws Exception {
		HttpResponse<String> response = send("POST", "/api/v1/auth/login", null,
				"{\"identifier\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body()).path("accessToken").asText();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private HttpResponse<String> get(String path, String bearer) throws Exception {
		HttpResponse<String> response = send("GET", path, bearer);
		assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
		return response;
	}

	private HttpResponse<byte[]> getBytes(String path, String bearer) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (bearer != null) request.header("Authorization", "Bearer " + bearer);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private HttpResponse<String> send(String method, String path, String bearer) throws Exception {
		return send(method, path, bearer, null);
	}

	private HttpResponse<String> send(String method, String path, String bearer, String body, String... headers)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (bearer != null) request.header("Authorization", "Bearer " + bearer);
		for (int i = 0; i + 1 < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
