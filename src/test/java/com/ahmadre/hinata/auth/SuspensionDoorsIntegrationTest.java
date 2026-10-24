package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.oauth.OAuthGrant;
import com.ahmadre.hinata.pat.PatService;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A suspended account is out at every door (HIN-172): the session it had, a refresh, a new
 * sign-in by password, an access token and an OAuth refresh. Over real HTTP, because the doors
 * are filters, the token endpoint and the sign-in, not one method.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SuspensionDoorsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String PASSWORD = "Sperre-Test-2026!";
	private static final String OAUTH_REFRESH = "test-refresh-token-for-the-suspended";
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private UserRepository users;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private PasswordEncoder passwords;
	@Autowired
	private SuspensionService suspensions;
	@Autowired
	private PatService pats;

	@LocalServerPort
	private int port;

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10)).build();

	private User admin;
	private User member;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "sessions", "personal_access_tokens", "oauth_grants",
				"oauth_clients")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		admin = account("ada", Set.of(Role.ADMIN, Role.MEMBER));
		member = account("jonas", Set.of(Role.MEMBER));
	}

	private User account(String name, Set<Role> roles) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.passwordHash(passwords.encode(PASSWORD)).roles(roles).active(true).build());
	}

	@Test
	void aSuspendedAccountIsOutAtEveryDoor() throws Exception {
		JsonNode signedIn = parse(post("/api/v1/auth/login", "{\"identifier\":\"jonas\",\"password\":\"" + PASSWORD
				+ "\"}", null));
		String access = signedIn.path("accessToken").asText();
		String refresh = signedIn.path("refreshToken").asText();
		String pat = pats.create(member, "Skript", Set.of("issues:read"), Duration.ofDays(30)).plaintext();
		String clientId = parse(post("/oauth/register", "{\"client_name\":\"Test AI\",\"redirect_uris\":"
				+ "[\"https://example.com/callback\"],\"token_endpoint_auth_method\":\"none\"}", null))
				.path("client_id").asText();
		mongo.insert(OAuthGrant.builder().userId(member.getId()).clientId(clientId).scopes(Set.of("issues:read"))
				.refreshTokenHash(sha256(OAUTH_REFRESH)).createdAt(Instant.now())
				.expiresAt(Instant.now().plus(Duration.ofDays(30))).build());
		assertThat(get("/api/v1/me", access).statusCode()).isEqualTo(200);

		// As MongoDB keeps it: to the millisecond.
		Instant until = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MILLIS);
		suspensions.suspend(member, until, "case-1", admin);

		assertThat(get("/api/v1/me", access).statusCode()).as("the session it had").isEqualTo(401);
		assertThat(get("/api/v1/me", pat).statusCode()).as("an access token").isEqualTo(401);
		HttpResponse<String> refreshed = send(json("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}", null));
		assertThat(refreshed.statusCode()).as("a refresh").isIn(401, 403);
		HttpResponse<String> again = send(json("/api/v1/auth/login",
				"{\"identifier\":\"jonas\",\"password\":\"" + PASSWORD + "\"}", null));
		assertThat(again.statusCode()).as("a new sign-in").isEqualTo(403);
		JsonNode refusal = JSON.readTree(again.body());
		assertThat(refusal.path("details").path("code").asText()).isEqualTo("accountSuspended");
		assertThat(refusal.path("details").path("until").asText()).isEqualTo(until.toString());
		HttpResponse<String> oauth = send(HttpRequest.newBuilder(url("/oauth/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&refresh_token="
						+ URLEncoder.encode(OAUTH_REFRESH, StandardCharsets.UTF_8) + "&client_id="
						+ URLEncoder.encode(clientId, StandardCharsets.UTF_8))).build());
		assertThat(oauth.statusCode()).as("an OAuth refresh").isEqualTo(400);
		assertThat(JSON.readTree(oauth.body()).path("error").asText()).isEqualTo("invalid_grant");

		suspensions.lift(users.findById(member.getId()).orElseThrow(), SuspensionService.LiftReason.EXPIRED, null, null);
		assertThat(send(json("/api/v1/auth/login", "{\"identifier\":\"jonas\",\"password\":\"" + PASSWORD + "\"}",
				null)).statusCode()).as("back in once it ends").isEqualTo(200);
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> get(String path, String bearer) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + bearer).GET().build());
	}

	private HttpRequest json(String path, String body, String bearer) {
		HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return request.build();
	}

	private String post(String path, String body, String bearer) {
		HttpResponse<String> response = send(json(path, body, bearer));
		assertThat(response.statusCode()).as(path + ": " + response.body()).isBetween(200, 299);
		return response.body();
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception ex) {
			throw new AssertionError("HTTP failed: " + request.uri(), ex);
		}
	}

	private static JsonNode parse(String body) throws Exception {
		return JSON.readTree(body);
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
