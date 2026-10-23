package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.RefreshSession;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time sign-out of HIN-179: every account an SSO or LDAP sign-in could have entered by
 * address alone loses its sessions, and nobody else does; with any such sign-in on, that is
 * everybody. Against a real MongoDB, because who is chosen is a question of what the users and
 * the audit log hold.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SsoSessionRevocationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SsoSessionRevocation migration;
	@Autowired
	private UserRepository users;
	@Autowired
	private SessionService sessions;
	@Autowired
	private SettingsService settings;

	@BeforeEach
	void clean() {
		// Clearing the marker puts each test back in the position of a server that has not
		// run this yet; the context boot already ran it on an empty database.
		mongo.remove(new Query(), User.class);
		mongo.remove(new Query(), AuditLog.class);
		mongo.remove(new Query(), RefreshSession.class);
		mongo.getCollection(MigrationMarkers.COLLECTION).deleteMany(new Document());
	}

	@AfterEach
	void signInsBackToDefault() {
		ServerSettings current = settings.get();
		current.getLdap().setEnabled(false);
		current.getOidc().setEnabled(false);
		current.getApp().setLocalAuthEnabled(null);
		settings.save(current);
	}

	@Test
	@DisplayName("signs out the accounts SSO made and the ones an SSO sign-in entered, nobody else")
	void signsOutTheAccountsReachableByAddress() {
		User local = signedIn(account("local@example.org", User.Origin.LOCAL));
		User enteredBySso = signedIn(account("admin@example.org", User.Origin.LOCAL));
		User madeBySso = signedIn(account("grace@example.org", User.Origin.OIDC));
		mongo.insert(AuditLog.builder().action(AuditAction.SSO_LOGIN).actorId(enteredBySso.getId())
				.timestamp(Instant.now()).build());

		migration.run(null);

		assertThat(sessions.list(local.getId())).hasSize(1);
		assertThat(sessions.list(enteredBySso.getId())).isEmpty();
		assertThat(sessions.list(madeBySso.getId())).isEmpty();
	}

	@Test
	@DisplayName("with LDAP on, everybody signs in again once")
	void withLdapOnEverybodySignsInAgain() {
		ServerSettings current = settings.get();
		current.getLdap().setEnabled(true);
		settings.save(current);
		User local = signedIn(account("local@example.org", User.Origin.LOCAL));

		migration.run(null);

		assertThat(sessions.list(local.getId())).isEmpty();
	}

	@Test
	@DisplayName("with an SSO sign-in on, everybody signs in again once: the audit log may not know whom it entered")
	void withSsoOnEverybodySignsInAgain() {
		ServerSettings current = settings.get();
		current.getOidc().setEnabled(true);
		settings.save(current);
		User local = signedIn(account("local@example.org", User.Origin.LOCAL));

		migration.run(null);

		assertThat(sessions.list(local.getId())).isEmpty();
	}

	@Test
	@DisplayName("with local sign-in off, everybody signs in again once")
	void withLocalSignInOffEverybodySignsInAgain() {
		ServerSettings current = settings.get();
		current.getApp().setLocalAuthEnabled(false);
		settings.save(current);
		User local = signedIn(account("local@example.org", User.Origin.LOCAL));

		migration.run(null);

		assertThat(sessions.list(local.getId())).isEmpty();
	}

	@Test
	@DisplayName("ends only sessions from before it started, a session of unknown age included")
	void endsOnlySessionsFromBeforeItStarted() {
		User grace = account("grace@example.org", User.Origin.OIDC);
		// Signed in while the migration runs: the server takes requests before it is through.
		RefreshSession fresh = mongo.insert(RefreshSession.builder().userId(grace.getId())
				.createdAt(Instant.now().plusSeconds(60)).lastActiveAt(Instant.now()).build());
		RefreshSession unknownAge = mongo.insert(RefreshSession.builder().userId(grace.getId())
				.lastActiveAt(Instant.now()).build());

		migration.run(null);

		assertThat(sessions.list(grace.getId())).extracting(RefreshSession::getId)
				.containsExactly(fresh.getId())
				.doesNotContain(unknownAge.getId());
	}

	@Test
	@DisplayName("runs once: a session started after it stays")
	void runsOnce() {
		migration.run(null);
		User grace = signedIn(account("grace@example.org", User.Origin.OIDC));

		migration.run(null);

		assertThat(sessions.list(grace.getId())).hasSize(1);
	}

	private User account(String email, User.Origin origin) {
		return users.save(User.builder().email(email).username(email.substring(0, email.indexOf('@')))
				.displayName(email).origin(origin).build());
	}

	private User signedIn(User user) {
		sessions.start(user, "203.0.113.7", "test");
		return user;
	}
}
