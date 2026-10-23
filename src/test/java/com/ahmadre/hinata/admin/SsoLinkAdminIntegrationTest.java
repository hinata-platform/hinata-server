package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.sso.SsoProfileMapper;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.SsoIdentity;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An administrator can undo an account's binding to its SSO identity (HIN-179), for the
 * person whose key at the provider changed and who no longer gets in. Over real HTTP: the
 * route sits under the administrators' prefix, and only they may use it, because the next
 * sign-in with the address binds the account again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SsoLinkAdminIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String PASSWORD = "a-long-enough-password-1";
	private static final String ISSUER = "https://idp.example.org";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;
	@Autowired
	private UserService accounts;
	@Autowired
	private com.ahmadre.hinata.user.SsoAccounts ssoAccounts;
	@Autowired
	private com.ahmadre.hinata.me.SessionService sessions;
	@Autowired
	private UserRepository users;
	@Autowired
	private MongoTemplate mongo;

	private User admin;
	private User grace;

	@BeforeEach
	void accounts() {
		mongo.remove(new Query(), User.class);
		mongo.remove(new Query(), AuditLog.class);
		mongo.remove(new Query(), com.ahmadre.hinata.me.RefreshSession.class);
		admin = accounts.createLocal("admin@example.org", "admin", "Admin", PASSWORD,
				Set.of(Role.ADMIN, Role.MEMBER));
		accounts.createLocal("member@example.org", "member", "Member", PASSWORD, Set.of(Role.MEMBER));
		grace = ssoAccounts.signIn(new SsoIdentity("oidc", ISSUER, "grace"),
				new SsoProfileMapper.SsoProfile("grace@example.org", "Grace Hopper", null), User.Origin.OIDC, true)
				.user();
	}

	@Test
	@DisplayName("the admin view says whether an account is bound")
	void theAdminViewSaysWhetherAnAccountIsBound() throws Exception {
		String token = login("admin");

		assertThat(body(send("GET", "/api/v1/admin/users/" + grace.getId(), token)).path("ssoLinked").asBoolean())
				.isTrue();
		assertThat(body(send("GET", "/api/v1/admin/users/" + admin.getId(), token)).path("ssoLinked").asBoolean())
				.isFalse();
	}

	@Test
	@DisplayName("an administrator undoes the binding, recorded, and the next sign-in binds again")
	void anAdministratorUndoesTheBinding() throws Exception {
		HttpResponse<String> response = send("DELETE", "/api/v1/admin/users/" + grace.getId() + "/sso-link",
				login("admin"));

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(users.findById(grace.getId()).orElseThrow().getSsoLink()).isNull();
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.USER_SSO_LINK_REMOVED)),
				AuditLog.class))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorId()).isEqualTo(admin.getId());
					assertThat(entry.getTargetId()).isEqualTo(grace.getId());
					assertThat(entry.getMetadata()).containsEntry("subject", "grace");
				});
		// The person's new key at the provider gets in, and the account is bound to it.
		User entered = ssoAccounts.signIn(new SsoIdentity("oidc", ISSUER, "grace-renamed"),
				new SsoProfileMapper.SsoProfile("grace@example.org", "Grace Hopper", null), User.Origin.OIDC, true)
				.user();
		assertThat(entered.getId()).isEqualTo(grace.getId());
	}

	@Test
	@DisplayName("undoing a binding signs out whoever was in through it")
	void undoingABindingEndsItsSessions() throws Exception {
		sessions.start(grace, "203.0.113.7", "test");
		sessions.start(grace, "203.0.113.8", "test");

		send("DELETE", "/api/v1/admin/users/" + grace.getId() + "/sso-link", login("admin"));

		assertThat(sessions.list(grace.getId())).isEmpty();
	}

	@Test
	@DisplayName("undoing a binding that is not there changes nothing and records nothing")
	void undoingNoBindingIsANoOp() throws Exception {
		User member = users.findByEmailIgnoreCase("member@example.org").orElseThrow();

		HttpResponse<String> response = send("DELETE", "/api/v1/admin/users/" + member.getId() + "/sso-link",
				login("admin"));

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.USER_SSO_LINK_REMOVED)),
				AuditLog.class)).isZero();
	}

	@Test
	@DisplayName("the user list sorts by last activity and counts sessions from one read of all of them")
	void theListSortsByActivity() throws Exception {
		String token = login("admin");
		// grace was active last, member never; admin has the session of this very login.
		sessions.start(grace, "203.0.113.7", "test");
		sessions.start(grace, "203.0.113.8", "test");

		JsonNode items = body(send("GET", "/api/v1/admin/users?sort=lastActive&dir=desc", token)).path("items");

		assertThat(items.get(0).path("email").asText()).isEqualTo("grace@example.org");
		assertThat(items.get(0).path("sessions").asInt()).isEqualTo(2);
		assertThat(items.get(1).path("email").asText()).isEqualTo("admin@example.org");
		assertThat(items.get(1).path("sessions").asInt()).isEqualTo(1);
		assertThat(items.get(2).path("sessions").asInt()).isZero();
	}

	@Test
	@DisplayName("nobody but an administrator may undo a binding")
	void onlyAnAdministratorMayUndoABinding() throws Exception {
		HttpResponse<String> response = send("DELETE", "/api/v1/admin/users/" + grace.getId() + "/sso-link",
				login("member"));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(users.findById(grace.getId()).orElseThrow().getSsoLink()).isNotNull();
	}

	private String login(String identifier) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
						java.util.Map.of("identifier", identifier, "password", PASSWORD))))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return body(response).path("accessToken").asText();
	}

	private HttpResponse<String> send(String method, String path, String token) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path))
				.header("Authorization", "Bearer " + token)
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static JsonNode body(HttpResponse<String> response) throws Exception {
		return JSON.readTree(response.body());
	}
}
