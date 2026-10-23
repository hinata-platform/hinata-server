package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An LDAP sign-in is bound to the entry's place in the directory, not to its {@code mail}
 * attribute (HIN-179): an entry that carries somebody else's address must not enter their
 * account. Over a real bind against an in-memory directory, the way the server talks to
 * Active Directory or OpenLDAP.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class LdapSignInIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String BASE = "dc=example,dc=org";
	private static final String PASSWORD = "directory-secret-1";
	private static InMemoryDirectoryServer directory;

	@Autowired
	private AuthService auth;
	@Autowired
	private UserRepository users;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

	@BeforeAll
	static void startDirectory() throws Exception {
		InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
		config.addAdditionalBindCredentials("cn=manager," + BASE, "manager-secret");
		config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
		// The standard schema, where entryUUID is an operational attribute as in OpenLDAP, 389 DS
		// and Synology: a directory sends it only to a search that asks for it by name.
		directory = new InMemoryDirectoryServer(config);
		directory.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: example");
		directory.add("dn: ou=people," + BASE, "objectClass: top", "objectClass: organizationalUnit",
				"ou: people");
		person("grace", "grace@example.org", "Grace Hopper");
		person("ada", "ada@example.org", "Ada Lovelace");
		// An entry whose mail attribute is the local administrator's address: in many
		// directories people maintain that attribute themselves.
		person("mallory", "admin@example.org", "Mallory");
		directory.startListening();
	}

	@AfterAll
	static void stopDirectory() {
		directory.shutDown(true);
	}

	private static void person(String uid, String mail, String name) throws Exception {
		directory.add("dn: uid=" + uid + ",ou=people," + BASE, "objectClass: top",
				"objectClass: inetOrgPerson", "uid: " + uid, "cn: " + name, "sn: " + name, "mail: " + mail,
				"userPassword: " + PASSWORD);
	}

	@BeforeEach
	void configure() {
		mongo.remove(new Query(), User.class);
		mongo.remove(new Query(), AuditLog.class);
		mongo.remove(new Query(), LoginAttempt.class);
		ServerSettings current = settings.get();
		ServerSettings.Ldap ldap = current.getLdap();
		ldap.setEnabled(true);
		ldap.setUrl(url());
		ldap.setBaseDn(BASE);
		ldap.setManagerDn("cn=manager," + BASE);
		ldap.setManagerPassword("manager-secret");
		ldap.setUserSearchBase("ou=people");
		ldap.setUserSearchFilter("(uid={0})");
		settings.save(current);
	}

	@AfterEach
	void restoreMail() throws LDAPException {
		directory.modify("uid=grace,ou=people," + BASE,
				new Modification(ModificationType.REPLACE, "mail", "grace@example.org"));
	}

	@Test
	@DisplayName("an entry carrying a local account's address does not enter it")
	void anEntryWithSomebodyElsesAddressIsRefused() {
		User admin = users.save(User.builder().email("admin@example.org").username("admin")
				.displayName("Admin").passwordHash("$2a$04$notarealhashnotarealhashnotarealhashnotareal")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).build());

		assertThatThrownBy(() -> auth.login("mallory", PASSWORD, "203.0.113.7", "test"))
				.isInstanceOfSatisfying(ApiException.class, refused ->
						assertThat(refused.getMessageKey()).isEqualTo("error.auth.ssoAccountNotLinked"));
		assertThat(users.findById(admin.getId()).orElseThrow().getSsoLink()).isNull();
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.LOGIN_FAILURE)), AuditLog.class))
				.singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata()).containsEntry("reason", "ssoAccountNotLinked"));
	}

	@Test
	@DisplayName("a sign-in binds the account to the entry's own id in the directory")
	void aSignInBindsTheAccountToTheEntrysOwnId() throws LDAPException {
		User grace = auth.login("grace", PASSWORD, "203.0.113.7", "test").user();

		// Operational: the directory sends it only when asked for it by name.
		String uuid = directory.getEntry("uid=grace,ou=people," + BASE, "entryUUID").getAttributeValue("entryUUID");
		assertThat(uuid).isNotBlank();
		// The directory is named by its base DN, so ldaps instead of ldap is no new provider.
		assertThat(users.findBySsoLink("ldap", BASE, uuid.toLowerCase(java.util.Locale.ROOT)))
				.get().extracting(User::getId).isEqualTo(grace.getId());
		assertThat(grace.getOrigin()).isEqualTo(User.Origin.LDAP);
	}

	@Test
	@DisplayName("a renamed entry still finds its account")
	void aRenamedEntryStillFindsItsAccount() throws LDAPException {
		User ada = auth.login("ada", PASSWORD, "203.0.113.7", "test").user();

		directory.modifyDN(new ModifyDNRequest("uid=ada,ou=people," + BASE, "uid=alovelace", true));

		assertThat(auth.login("alovelace", PASSWORD, "203.0.113.7", "test").user().getId()).isEqualTo(ada.getId());
	}

	@Test
	@DisplayName("the entry still finds its account after the address changed in the directory")
	void theEntryFindsItsAccountAfterAnAddressChange() throws LDAPException {
		User grace = auth.login("grace", PASSWORD, "203.0.113.7", "test").user();
		directory.modify("uid=grace,ou=people," + BASE,
				new Modification(ModificationType.REPLACE, "mail", "grace.hopper@example.org"));

		assertThat(auth.login("grace", PASSWORD, "203.0.113.7", "test").user().getId()).isEqualTo(grace.getId());
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a deactivated account stays out")
	void aDeactivatedAccountStaysOut() {
		User grace = auth.login("grace", PASSWORD, "203.0.113.7", "test").user();
		grace = users.findById(grace.getId()).orElseThrow();
		grace.setActive(false);
		users.save(grace);

		assertThatThrownBy(() -> auth.login("grace", PASSWORD, "203.0.113.7", "test"))
				.isInstanceOfSatisfying(ApiException.class, refused ->
						assertThat(refused.getMessageKey()).isEqualTo("error.auth.accountDeactivated"));
	}

	@Test
	@DisplayName("refusals for the account's state do not lock the person out like wrong passwords")
	void refusalsForTheAccountsStateDoNotLockOut() {
		User grace = auth.login("grace", PASSWORD, "203.0.113.7", "test").user();
		setActive(grace, false);
		for (int attempt = 0; attempt < 6; attempt++) {
			assertThatThrownBy(() -> auth.login("grace", PASSWORD, "203.0.113.7", "test"))
					.isInstanceOf(SignInRefusedException.class);
		}

		setActive(grace, true);

		assertThat(auth.login("grace", PASSWORD, "203.0.113.7", "test").user().getId()).isEqualTo(grace.getId());
	}

	@Test
	@DisplayName("a password sign-in by username is not shut out by an account whose address is that name")
	void anAddressThatIsSomebodysUsernameDoesNotShutThemOut() {
		ServerSettings current = settings.get();
		current.getLdap().setEnabled(false);
		settings.save(current);
		// Such an address came from a provider that sent none before HIN-179 refused to keep one.
		users.save(User.builder().email("admin").username("admin-sso").displayName("Not the admin")
				.origin(User.Origin.OIDC).build());
		User admin = users.save(User.builder().email("admin@example.org").username("admin").displayName("Admin")
				.passwordHash(passwordEncoder.encode(PASSWORD)).roles(Set.of(Role.ADMIN, Role.MEMBER)).build());

		assertThat(auth.login("admin", PASSWORD, "203.0.113.7", "test").user().getId()).isEqualTo(admin.getId());
	}

	private void setActive(User user, boolean active) {
		User stored = users.findById(user.getId()).orElseThrow();
		stored.setActive(active);
		users.save(stored);
	}

	private static String url() {
		return "ldap://localhost:" + directory.getListenPort();
	}
}
