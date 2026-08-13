package com.ahmadre.hinata.auth.sso;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ahmadre.hinata.setup.ServerSettings;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SsoProfileMapperTest {

	private final SsoProfileMapper mapper = new SsoProfileMapper();

	@Test
	void readsTheStandardOidcClaims() {
		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", "grace@example.org",
				"name", "Grace Hopper",
				"job_title", "Rear Admiral"), new ServerSettings.Oidc(), "sub-42");

		assertThat(profile.email()).isEqualTo("grace@example.org");
		assertThat(profile.displayName()).isEqualTo("Grace Hopper");
		assertThat(profile.title()).isEqualTo("Rear Admiral");
	}

	@Test
	void takesTheFirstNonBlankAlternative() {
		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", "grace@example.org",
				"name", "   ",
				"preferred_username", "ghopper"), new ServerSettings.Oidc(), "sub-42");

		assertThat(profile.displayName()).isEqualTo("ghopper");
	}

	@Test
	void joinsASpaceSeparatedCandidateOnlyWhenEveryPartIsPresent() {
		ServerSettings.Oidc config = new ServerSettings.Oidc();

		SsoProfileMapper.SsoProfile both = mapper.map(Map.of(
				"email", "grace@example.org",
				"given_name", "Grace",
				"family_name", "Hopper"), config, "sub-42");
		assertThat(both.displayName()).isEqualTo("Grace Hopper");

		// Half a name is worse than the next alternative — here: none, so the
		// address is humanized instead of yielding a bare "Grace".
		SsoProfileMapper.SsoProfile half = mapper.map(Map.of(
				"email", "grace.hopper@example.org",
				"given_name", "Grace"), config, "sub-42");
		assertThat(half.displayName()).isEqualTo("Grace Hopper");
	}

	@Test
	void matchesAttributeNamesCaseInsensitively() {
		ServerSettings.Ldap config = new ServerSettings.Ldap();

		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"MAIL", "grace@example.org",
				"CN", "Grace Hopper"), config, "grace@ldap.local");

		assertThat(profile.email()).isEqualTo("grace@example.org");
		assertThat(profile.displayName()).isEqualTo("Grace Hopper");
	}

	@Test
	void flattensMultiValuedAttributes() {
		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", List.of("grace@example.org"),
				"displayName", List.of("Grace Hopper"),
				"title", List.of("Rear Admiral")), new ServerSettings.Saml(), "grace");

		assertThat(profile.email()).isEqualTo("grace@example.org");
		assertThat(profile.displayName()).isEqualTo("Grace Hopper");
		assertThat(profile.title()).isEqualTo("Rear Admiral");
	}

	@Test
	void readsTheDescriptionAsPositionOverLdap() {
		// Directories that have no title attribute (a DSM user list, for one) keep
		// the position in the entry's description.
		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"mail", "ada.lovelace@example.org",
				"cn", "ALovelace",
				"description", "Manager"), new ServerSettings.Ldap(), "alovelace@ldap.local");

		assertThat(profile.displayName()).isEqualTo("ALovelace");
		assertThat(profile.title()).isEqualTo("Manager");
	}

	@Test
	void derivesANameFromTheAddressWhenTheIdpSendsNone() {
		// Some providers ship no name claim at all; the address still carries one.
		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", "ada.lovelace@example.org",
				"username", "ALovelace"), new ServerSettings.Oidc(), "sub-42");

		assertThat(profile.displayName()).isEqualTo("Ada Lovelace");
		assertThat(profile.title()).isNull();
	}

	@Test
	void keepsRoleMailboxesAndHandlesAsTheyAre() {
		ServerSettings.Oidc config = new ServerSettings.Oidc();

		assertThat(mapper.map(Map.of("email", "it@example.org"), config, "sub").displayName()).isNull();
		assertThat(mapper.map(Map.of("email", "a1b2c3@example.org"), config, "sub").displayName()).isNull();
		assertThat(mapper.map(Map.of("email", "a.b@example.org"), config, "sub").displayName()).isNull();
	}

	@Test
	void honoursAnAdminOverride() {
		ServerSettings.Oidc config = new ServerSettings.Oidc();
		config.setDisplayNameAttribute("username");
		config.setTitleAttribute("description");

		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", "ada.lovelace@example.org",
				"username", "ALovelace",
				"description", "Manager"), config, "sub-42");

		assertThat(profile.displayName()).isEqualTo("ALovelace");
		assertThat(profile.title()).isEqualTo("Manager");
	}

	@Test
	void fallsBackToThePrincipalNameWhenTheAddressIsMissing() {
		SsoProfileMapper.SsoProfile profile =
				mapper.map(Map.of("sub", "42"), new ServerSettings.Oidc(), "grace@example.org");

		assertThat(profile.email()).isEqualTo("grace@example.org");
	}

	@Test
	void logsAttributeNamesAtDebugAndNeverTheirValues() {
		List<String> lines = captureLog(Level.DEBUG, () -> mapper.map(Map.of(
				"email", "ada.lovelace@example.org",
				"username", "ALovelace"), new ServerSettings.Oidc(), "sub-42"));

		assertThat(lines).anyMatch(line -> line.contains("email") && line.contains("username"));
		// The whole point of the level split: values are personal data.
		assertThat(lines).noneMatch(line -> line.contains("ada.lovelace@example.org"));
		assertThat(lines).noneMatch(line -> line.contains("ALovelace"));
	}

	@Test
	void logsEachAttributeWithItsValueAndTypeAtTrace() {
		List<String> lines = captureLog(Level.TRACE, () -> mapper.map(Map.of(
				"email", "ada.lovelace@example.org",
				"groups", List.of("admins", "staff")), new ServerSettings.Oidc(), "sub-42"));

		assertThat(lines).anyMatch(line ->
				line.contains("email") && line.contains("ada.lovelace@example.org") && line.contains("String"));
		// A claim arriving as a list is exactly what an admin needs to see.
		assertThat(lines).anyMatch(line -> line.contains("groups") && line.contains("admins"));
	}

	@Test
	void capsAnOversizedAttributeValue() {
		String huge = "x".repeat(1000);
		List<String> lines = captureLog(Level.TRACE, () -> mapper.map(Map.of(
				"email", "ada.lovelace@example.org", "picture", huge),
				new ServerSettings.Oidc(), "sub-42"));

		assertThat(lines).anyMatch(line -> line.contains("picture") && line.contains("(1000 chars)"));
		assertThat(lines).noneMatch(line -> line.contains(huge));
	}

	/** Runs [action] with the mapper's logger at [level] and returns the formatted lines. */
	private List<String> captureLog(Level level, Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(SsoProfileMapper.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Level original = logger.getLevel();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(level);
		try {
			action.run();
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}
		finally {
			logger.setLevel(original);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void resolvesNothingFromAnEmptyMapping() {
		ServerSettings.Oidc config = new ServerSettings.Oidc();
		config.setTitleAttribute("");

		SsoProfileMapper.SsoProfile profile = mapper.map(Map.of(
				"email", "grace@example.org",
				"title", "Rear Admiral"), config, "sub-42");

		assertThat(profile.title()).isNull();
	}
}
