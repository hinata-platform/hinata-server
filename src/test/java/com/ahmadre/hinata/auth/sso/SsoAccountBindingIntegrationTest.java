package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.InviteController;
import com.ahmadre.hinata.auth.SignInRefusal;
import com.ahmadre.hinata.auth.SignInRefusedException;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.SsoAccounts;
import com.ahmadre.hinata.user.SsoIdentity;
import com.ahmadre.hinata.user.SsoLink;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An SSO sign-in enters the account bound to the provider's key for the person, never simply
 * the account that happens to have the address the provider sent (HIN-179).
 *
 * <p>Before the fix any identity whose provider-side address matched a Hinata account was let
 * into it: a local administrator's account with password and 2FA included, by anyone who could
 * type that address into their profile at the provider. And an account an administrator had
 * deactivated got fresh tokens over SSO. Against a real MongoDB, because the binding's
 * uniqueness and the race for an unbound account are the database's to decide.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.cors.allowed-origins=https://app.example.org"
})
@Testcontainers(disabledWithoutDocker = true)
class SsoAccountBindingIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String ISSUER = "https://idp.example.org/realms/staff";
	private static final String SAML_METADATA = "classpath:saml/idp-metadata.xml";
	/** The entity id in {@link #SAML_METADATA}. */
	private static final String SAML_ENTITY = "https://idp.example.org/saml";
	private static final String WEB_APP = "https://app.example.org";
	private static final String INVITE_SECRET = "s3cret-from-the-invitation-mail";
	/** The S256 pair from RFC 7636, appendix B: what an app keeps, and what it sends to the start. */
	private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	@Autowired
	private SsoAccounts accounts;
	@Autowired
	private UserRepository users;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private SsoLoginSuccessHandler handler;
	@Autowired
	private SessionService sessions;
	@Autowired
	private MeService me;
	@Autowired
	private SsoController ssoController;
	@Autowired
	private InviteController inviteController;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void clean() {
		// Documents only: dropping the collections would drop the unique indexes under test.
		mongo.remove(new Query(), User.class);
		mongo.remove(new Query(), AuditLog.class);
		mongo.remove(new Query(), Notification.class);
		ServerSettings current = settings.get();
		current.getOidc().setIssuerUri(ISSUER);
		current.getOauth2().setAuthorizationUri("https://git.example.org/oauth/authorize");
		current.getSaml().setEnabled(false);
		current.getSaml().setIdpMetadataUri(SAML_METADATA);
		settings.save(current);
	}

	@AfterEach
	void restoreLocalSignIn() {
		ServerSettings current = settings.get();
		current.getApp().setLocalAuthEnabled(null);
		settings.save(current);
	}

	// --- the takeover ----------------------------------------------------------

	@Test
	@DisplayName("an identity with the address of a local account does not enter it")
	void aLocalAccountIsNotEnteredByItsAddress() {
		User admin = local("admin@example.org");

		assertRefused(() -> sso(identity("mallory"), "admin@example.org"), SignInRefusal.SSO_ACCOUNT_NOT_LINKED);
		assertThat(reload(admin).getSsoLink()).isNull();
	}

	@Test
	@DisplayName("a second identity with the address of a bound account is refused")
	void aBoundAccountRefusesAnotherSubject() {
		User grace = sso(identity("grace"), "grace@example.org");

		assertRefused(() -> sso(identity("mallory"), "grace@example.org"), SignInRefusal.SSO_ACCOUNT_NOT_LINKED);
		assertThat(reload(grace).getSsoLink().subject()).isEqualTo("grace");
	}

	@Test
	@DisplayName("an account made by another kind of sign-in is not entered by its address")
	void anAccountOfAnotherKindIsNotEnteredByItsAddress() {
		User viaSaml = account("ada@example.org", User.Origin.SAML, null);

		assertRefused(() -> sso(identity("ada"), "ada@example.org"), SignInRefusal.SSO_ACCOUNT_NOT_LINKED);
		assertThat(reload(viaSaml).getSsoLink()).isNull();
	}

	@Test
	@DisplayName("retyping the provider's address with a trailing slash is no switch of provider")
	void aTrailingSlashIsTheSameProvider() {
		User grace = sso(identity("grace"), "grace@example.org");

		assertRefused(() -> sso(new SsoIdentity("oidc", ISSUER + "/", "mallory"), "grace@example.org"),
				SignInRefusal.SSO_ACCOUNT_NOT_LINKED);
		assertThat(sso(new SsoIdentity("oidc", ISSUER + "/", "grace"), "grace@example.org").getId())
				.isEqualTo(grace.getId());
	}

	// --- a refused sign-in leaves nothing behind -----------------------------------

	@Test
	@DisplayName("an identity refused for the account's state does not bind it, nor learn that state")
	void aRefusedSignInBindsNothing() {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);
		earlier.setActive(false);
		users.save(earlier);

		assertRefused(() -> sso(identity("mallory"), "grace@example.org"), SignInRefusal.SSO_ACCOUNT_NOT_LINKED,
				SignInRefusal.ACCOUNT_DEACTIVATED);
		assertThat(reload(earlier).getSsoLink()).isNull();
		// Reactivated, the account is still nobody's: the next sign-in decides, not the refused one.
		earlier = reload(earlier);
		earlier.setActive(true);
		users.save(earlier);
		assertThat(sso(identity("grace"), "grace@example.org").getId()).isEqualTo(earlier.getId());
		assertThat(reload(earlier).getSsoLink().subject()).isEqualTo("grace");
	}

	@Test
	@DisplayName("with SSO the only way in, an open invitation is not bound to whoever claims its address")
	void anOpenInvitationIsNotBound() {
		localSignIn(false);
		User invited = users.save(User.builder().email("neu@example.org").username("neu").displayName("Neu")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).origin(User.Origin.LOCAL).active(false)
				.emailVerified(false).invitedAt(Instant.now()).build());

		// Nor is it told the invitation is open, which would say when to try again.
		assertRefused(() -> sso(identity("mallory"), "neu@example.org"), SignInRefusal.SSO_ACCOUNT_NOT_LINKED,
				SignInRefusal.EMAIL_NOT_VERIFIED);
		assertThat(reload(invited).getSsoLink()).isNull();
	}

	@Test
	@DisplayName("an address the provider calls unconfirmed binds no account that has it")
	void anUnconfirmedAddressBindsNoAccount() {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);

		assertRefused(() -> sso(identity("mallory"), "grace@example.org", false), SignInRefusal.SSO_EMAIL_NOT_VERIFIED);

		assertThat(reload(earlier).getSsoLink()).isNull();
		// A confirmed address and the account's own key still work as before.
		assertThat(sso(identity("grace"), "grace@example.org", true).getId()).isEqualTo(earlier.getId());
	}

	@Test
	@DisplayName("a new person whose address the provider calls unconfirmed still gets an account of their own")
	void anUnconfirmedAddressStillMakesANewAccount() {
		// Nothing is entered but a new, empty account: Keycloak, for one, calls every address it
		// took over from a directory unconfirmed until told to trust it.
		User created = sso(identity("kollegin"), "kollegin@example.org", false);

		assertThat(reload(created).getSsoLink().subject()).isEqualTo("kollegin");
	}

	@Test
	@DisplayName("a provider that sends no address makes no account")
	void aSignInWithoutAnAddressMakesNoAccount() {
		local("admin@example.org");

		// The principal's name stands where the address would be, the local admin's username here.
		assertRefused(() -> sso(identity("admin"), "admin"), SignInRefusal.SSO_NO_EMAIL);
		assertThat(users.count()).isEqualTo(1);
	}

	// --- what keeps working ------------------------------------------------------

	@Test
	@DisplayName("a first sign-in creates an account bound to the identity")
	void aFirstSignInCreatesABoundAccount() {
		User created = sso(identity("grace"), "grace@example.org");

		assertThat(created.getOrigin()).isEqualTo(User.Origin.OIDC);
		assertThat(reload(created).getSsoLink())
				.extracting(SsoLink::provider, SsoLink::issuer, SsoLink::subject)
				.containsExactly("oidc", ISSUER, "grace");
	}

	@Test
	@DisplayName("an SSO account from before binding is bound at its next sign-in and then found by its subject")
	void anUnboundSsoAccountIsBoundOnceThenFoundBySubject() {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);

		assertThat(sso(identity("grace"), "grace@example.org").getId()).isEqualTo(earlier.getId());
		// The address changed at the provider; the subject still finds the account, and the
		// address in Hinata stays what it was.
		assertThat(sso(identity("grace"), "grace.hopper@example.org").getId()).isEqualTo(earlier.getId());
		assertThat(reload(earlier).getEmail()).isEqualTo("grace@example.org");
		assertThat(audits(AuditAction.SSO_LINKED))
				.singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata())
						.containsEntry("registration", "oidc")
						.containsEntry("issuer", ISSUER)
						.containsEntry("subject", "grace")
						.containsEntry("email", "grace@example.org"));
	}

	@Test
	@DisplayName("the owner is told when the address binds their account, who and where, once")
	void theOwnerIsToldWhenTheAddressBindsTheirAccount() throws Exception {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);

		signIn(oidc("9f8e7d6c", "grace@example.org", null), false);

		// One notice: the one about a sign-in from a new device would say the same again.
		assertThat(securityAlerts(earlier)).singleElement().satisfies(alert -> {
			assertThat(alert.getBody()).contains("OpenID Connect").contains("grace@example.org");
			// The provider's key for the person means nothing to them.
			assertThat(alert.getBody()).doesNotContain("9f8e7d6c");
		});
		// A sign-in through the binding is no news.
		sso(new SsoIdentity("oidc", ISSUER, "9f8e7d6c"), "grace@example.org");
		assertThat(securityAlerts(earlier)).hasSize(1);
	}

	@Test
	@DisplayName("after the provider behind a registration changes, accounts bind again by address")
	void aSwitchOfIdentityProviderBindsAgainByAddress() {
		User grace = sso(identity("grace"), "grace@example.org");

		User entered = sso(new SsoIdentity("oidc", "https://login.example.net", "4f1c9"), "grace@example.org");

		assertThat(entered.getId()).isEqualTo(grace.getId());
		assertThat(reload(grace).getSsoLink())
				.extracting(SsoLink::issuer, SsoLink::subject)
				.containsExactly("https://login.example.net", "4f1c9");
	}

	@Test
	@DisplayName("with local sign-in off, SSO is the only way in and a local account is bound by its address")
	void aLocalAccountIsBoundWhileSsoIsTheOnlyWayIn() {
		localSignIn(false);
		User admin = local("admin@example.org");

		User entered = sso(identity("rahmad"), "admin@example.org");

		assertThat(entered.getId()).isEqualTo(admin.getId());
		assertThat(reload(admin).getOrigin()).isEqualTo(User.Origin.LOCAL);
		assertThat(reload(admin).getSsoLink().subject()).isEqualTo("rahmad");
	}

	@Test
	@DisplayName("no two accounts can hold the same identity")
	void anIdentityBelongsToOneAccount() {
		SsoLink link = new SsoLink("oidc", ISSUER, "grace", Instant.now());
		account("grace@example.org", User.Origin.OIDC, link);

		assertThatThrownBy(() -> account("grace.h@example.org", User.Origin.OIDC, link))
				.isInstanceOf(DuplicateKeyException.class);
	}

	// --- two sign-ins at once ------------------------------------------------------

	@Test
	@DisplayName("eight identities racing for one unbound account: exactly one gets it")
	void racingIdentitiesBindAnAccountOnce() throws Exception {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);

		List<Boolean> entered = race(8, i -> identity("subject-" + i), i -> "grace@example.org");

		assertThat(entered.stream().filter(Boolean::booleanValue)).hasSize(1);
		assertThat(reload(earlier).getSsoLink()).isNotNull();
	}

	@Test
	@DisplayName("the same person arriving twice at once lands in the account both times")
	void theSamePersonTwiceAtOnceEntersTwice() throws Exception {
		account("grace@example.org", User.Origin.OIDC, null);

		assertThat(race(2, i -> identity("grace"), i -> "grace@example.org")).containsOnly(true);
	}

	@Test
	@DisplayName("a new person arriving twice at once gets one account")
	void aNewPersonTwiceAtOnceGetsOneAccount() throws Exception {
		assertThat(race(2, i -> identity("grace"), i -> "grace@example.org")).containsOnly(true);
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("two new people with the same name before the @ at once get two accounts")
	void twoNewPeopleWithTheSameLocalPartGetTwoAccounts() throws Exception {
		assertThat(race(2, i -> identity("max-" + i), i -> "max@" + (i == 0 ? "a" : "b") + ".example.org"))
				.containsOnly(true);
		assertThat(users.findAll()).extracting(User::getUsername).doesNotHaveDuplicates().hasSize(2);
	}

	// --- the handler: what the browser and the audit log get -------------------

	@Test
	@DisplayName("the takeover through the real handler: refused with a code, recorded, no session")
	void theHandlerRefusesTheTakeover() throws Exception {
		User admin = local("admin@example.org");

		UriComponents back = signIn(oidc("mallory", "admin@example.org", null), false);

		assertThat(back.getQueryParams()).containsOnlyKeys("error");
		assertThat(param(back, "error")).isEqualTo("ssoAccountNotLinked");
		assertThat(sessions.list(admin.getId())).isEmpty();
		assertThat(audits(AuditAction.LOGIN_FAILURE))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorLabel()).isEqualTo("admin@example.org");
					assertThat(entry.getMetadata())
							.containsEntry("reason", "ssoAccountNotLinked")
							.containsEntry("provider", "OIDC")
							.containsEntry("registration", "oidc")
							.containsEntry("subject", "mallory");
				});
	}

	@Test
	@DisplayName("a deactivated account gets no tokens over SSO")
	void aDeactivatedAccountIsRefused() throws Exception {
		User grace = reload(sso(identity("grace"), "grace@example.org"));
		grace.setActive(false);
		users.save(grace);

		UriComponents back = signIn(oidc("grace", "grace@example.org", null), false);

		assertThat(param(back, "error")).isEqualTo("accountDeactivated");
		assertThat(back.getQueryParams()).doesNotContainKey("code");
		assertThat(sessions.list(grace.getId())).isEmpty();
		assertThat(audits(AuditAction.LOGIN_FAILURE))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorId()).isEqualTo(grace.getId());
					assertThat(entry.getMetadata()).containsEntry("reason", "accountDeactivated");
				});
	}

	@Test
	@DisplayName("an account still waiting for approval gets no tokens over SSO")
	void anAccountAwaitingApprovalIsRefused() throws Exception {
		User grace = reload(sso(identity("grace"), "grace@example.org"));
		grace.setAwaitingApproval(true);
		grace.setActive(false);
		users.save(grace);

		assertThat(param(signIn(oidc("grace", "grace@example.org", null), false), "error"))
				.isEqualTo("pendingApproval");
	}

	@Test
	@DisplayName("an OIDC provider that calls the address of an account unconfirmed gets the reason back")
	void anUnconfirmedOidcAddressIsRefused() throws Exception {
		account("kollegin@example.org", User.Origin.OIDC, null);

		assertThat(param(signIn(oidc("mallory", "kollegin@example.org", false), false), "error"))
				.isEqualTo("ssoEmailNotVerified");
	}

	@Test
	@DisplayName("whoever claims the address of a deactivated account learns nothing of its state")
	void theStateOfAnAccountIsNotToldToWhoeverClaimsItsAddress() throws Exception {
		User earlier = account("grace@example.org", User.Origin.OIDC, null);
		earlier.setActive(false);
		users.save(earlier);

		assertThat(param(signIn(oidc("mallory", "grace@example.org", null), false), "error"))
				.isEqualTo("ssoAccountNotLinked");
		assertThat(audits(AuditAction.LOGIN_FAILURE))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorId()).isEqualTo(earlier.getId());
					assertThat(entry.getMetadata()).containsEntry("reason", "accountDeactivated");
				});
	}

	@Test
	@DisplayName("a web sign-in gets the reason as a code on its login page")
	void aWebSignInGetsTheReasonOnItsLoginPage() throws Exception {
		local("admin@example.org");

		UriComponents back = signIn(oidc("mallory", "admin@example.org", null), true);

		assertThat(back.getScheme() + "://" + back.getHost()).isEqualTo(WEB_APP);
		assertThat(back.getPath()).isEqualTo("/login");
		assertThat(param(back, "ssoError")).isEqualTo("ssoAccountNotLinked");
	}

	@Test
	@DisplayName("a good SSO sign-in still gets its handoff code and binds the account")
	void aGoodSignInGetsItsCode() throws Exception {
		UriComponents back = signIn(oidc("grace", "grace@example.org", true), false);

		assertThat(back.toUriString()).startsWith("hinata://auth-callback?code=");
		assertThat(users.findBySsoLink("oidc", ISSUER, "grace")).isPresent();
	}

	@Test
	@DisplayName("a handoff code is redeemed only with the verifier of the app that started the sign-in")
	void aBoundCodeTakesTheVerifierOfItsApp() throws Exception {
		String code = param(signIn(oidc("grace", "grace@example.org", true), false, null, CHALLENGE), "code");

		assertInvalidCode(() -> ssoController.exchange(new SsoController.ExchangeRequest(code, null)));
		assertInvalidCode(() -> ssoController.exchange(new SsoController.ExchangeRequest(code, "a".repeat(43))));
		// A link that carries the code signs no one else in, and leaves it to the app it belongs to.
		assertThat(ssoController.exchange(new SsoController.ExchangeRequest(code, VERIFIER)).accessToken())
				.isNotBlank();
		assertInvalidCode(() -> ssoController.exchange(new SsoController.ExchangeRequest(code, VERIFIER)));
	}

	@Test
	@DisplayName("a code from a sign-in started without a challenge is refused to an app that sends a verifier")
	void anUnboundCodeIsRefusedToAnAppThatSendsAVerifier() throws Exception {
		String code = param(signIn(oidc("grace", "grace@example.org", true), false), "code");

		assertInvalidCode(() -> ssoController.exchange(new SsoController.ExchangeRequest(code, VERIFIER)));
		// An app from before the binding sends none and still signs in.
		assertThat(ssoController.exchange(new SsoController.ExchangeRequest(code, null)).accessToken())
				.isNotBlank();
	}

	@Test
	@DisplayName("an OAuth2 provider named by the address is bound to the person's id instead")
	void anOAuth2ProviderIsBoundToTheIdNotTheAddress() throws Exception {
		signIn(oauth2(Map.of("id", 4711, "email", "grace@example.org"), "email"), false);

		UriComponents back = signIn(oauth2(Map.of("id", 666, "email", "grace@example.org"), "email"), false);

		assertThat(param(back, "error")).isEqualTo("ssoAccountNotLinked");
		// Named by the host it is reached at: retyping the path is no switch of provider.
		assertThat(users.findBySsoLink("oauth2", "git.example.org", "4711")).isPresent();
	}

	@Test
	@DisplayName("an OAuth2 provider without an id is bound to its name attribute")
	void anOAuth2ProviderWithoutAnIdFallsBackToItsNameAttribute() throws Exception {
		signIn(oauth2(Map.of("login", "ghopper", "email", "grace@example.org"), "login"), false);

		assertThat(users.findBySsoLink("oauth2", "git.example.org", "ghopper")).isPresent();
	}

	@Test
	@DisplayName("a SAML sign-in is bound to the NameID at the identity provider its entity id names")
	void aSamlSignInIsBoundToTheNameId() throws Exception {
		samlFrom(SAML_METADATA);

		UriComponents back = signIn(saml("grace-persistent-id", "grace@example.org"), false);

		assertThat(back.toUriString()).startsWith("hinata://auth-callback?code=");
		assertThat(users.findBySsoLink("saml", SAML_ENTITY, "grace-persistent-id"))
				.get().extracting(User::getOrigin).isEqualTo(User.Origin.SAML);
	}

	@Test
	@DisplayName("a SAML sign-in with no registration to name its provider is sent back, not bound anew")
	void aSamlSignInWithoutARegistrationIsSentBack() throws Exception {
		samlFrom(SAML_METADATA);
		signIn(saml("grace-persistent-id", "grace@example.org"), false);
		// SAML is switched off while the next one is under way.
		ServerSettings current = settings.get();
		current.getSaml().setEnabled(false);
		settings.save(current);

		UriComponents back = signIn(saml("grace-persistent-id", "grace@example.org"), false);

		assertThat(param(back, "error")).isEqualTo("sso_failed");
		assertThat(users.findByEmailIgnoreCase("grace@example.org").orElseThrow().getSsoLink().issuer())
				.isEqualTo(SAML_ENTITY);
		assertThat(audits(AuditAction.SSO_LINKED)).isEmpty();
	}

	@Test
	@DisplayName("a provider that names nobody is sent back, not answered with an error page")
	void aSignInWithoutASubjectGoesBack() throws Exception {
		samlFrom(SAML_METADATA);
		DefaultSaml2AuthenticatedPrincipal nobody = new DefaultSaml2AuthenticatedPrincipal("",
				Map.of("email", List.of("grace@example.org")));

		UriComponents back = signIn(new Saml2Authentication(nobody, "<Response/>", List.of()), false);

		assertThat(param(back, "error")).isEqualTo("sso_no_subject");
		assertThat(users.count()).isZero();
	}

	// --- an invitation completed by SSO -------------------------------------------

	@Test
	@DisplayName("a sign-in started from an invitation completes it and binds the account")
	void aSignInFromAnInvitationJoinsIt() throws Exception {
		localSignIn(false);
		User invited = invitation("neu@example.org");

		// The provider knows the person under another address: the invitation link proved this one.
		UriComponents back = signIn(oidc("neu-sub", "neu.person@idp.example.org", null), false,
				invited.getId() + "." + INVITE_SECRET);

		assertThat(back.toUriString()).startsWith("hinata://auth-callback?code=");
		User joined = reload(invited);
		assertThat(joined.getSsoLink().subject()).isEqualTo("neu-sub");
		assertThat(joined.isActive()).isTrue();
		assertThat(joined.isEmailVerified()).isTrue();
		assertThat(joined.isInvitePending()).isFalse();
		assertThat(joined.getInviteTokenHash()).isNull();
		assertThat(joined.getEmail()).isEqualTo("neu@example.org");
		assertThat(joined.getRoles()).contains(Role.ADMIN);
		assertThat(users.count()).isEqualTo(1);
		assertThat(audits(AuditAction.SSO_LINKED)).singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata()).containsEntry("invitation", "accepted"));
	}

	@Test
	@DisplayName("the invitation's address alone completes nothing")
	void anInvitationIsNotCompletedByItsAddress() throws Exception {
		localSignIn(false);
		User invited = invitation("neu@example.org");

		assertThat(param(signIn(oidc("mallory", "neu@example.org", null), false), "error"))
				.isEqualTo("ssoAccountNotLinked");
		assertThat(reload(invited).isInvitePending()).isTrue();
		assertThat(reload(invited).getSsoLink()).isNull();
	}

	@Test
	@DisplayName("a token that opens no invitation leaves an ordinary sign-in")
	void aWrongInvitationTokenCompletesNothing() throws Exception {
		localSignIn(false);
		User invited = invitation("neu@example.org");

		assertThat(param(signIn(oidc("mallory", "neu@example.org", null), false, invited.getId() + ".guessed"),
				"error")).isEqualTo("ssoAccountNotLinked");
		assertThat(reload(invited).isInvitePending()).isTrue();
	}

	@Test
	@DisplayName("a person with an account of their own enters it, and the invitation stays open")
	void anIdentityWithAnAccountKeepsIt() throws Exception {
		User own = sso(identity("grace"), "grace@example.org");
		User invited = invitation("grace.second@example.org");

		signIn(oidc("grace", "grace@example.org", null), false, invited.getId() + "." + INVITE_SECRET);

		assertThat(sessions.list(own.getId())).hasSize(1);
		assertThat(reload(invited).isInvitePending()).isTrue();
	}

	@Test
	@DisplayName("with local sign-in off, an invitation takes no password")
	void anInvitationTakesNoPasswordWhileLocalSignInIsOff() {
		localSignIn(false);
		User invited = invitation("neu@example.org");

		assertThatThrownBy(() -> inviteController.accept(new InviteController.AcceptRequest(
				invited.getId() + "." + INVITE_SECRET, "a-long-enough-password"), new MockHttpServletRequest()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getMessageKey()).isEqualTo("error.auth.localAuthDisabled"));
		assertThat(reload(invited).isInvitePending()).isTrue();
	}

	@Test
	@DisplayName("the SSO start keeps an invitation for the way back, and checks it first")
	void theStartKeepsTheInvitation() throws Exception {
		User invited = invitation("neu@example.org");
		String token = invited.getId() + "." + INVITE_SECRET;

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/sso/start/oidc");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ssoController.start("oidc", null, token, null, request, response);

		assertThat(request.getSession().getAttribute(SsoController.SESSION_INVITE)).isEqualTo(token);
		assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
				assertThat(cookie).startsWith(SsoController.COOKIE_INVITE + "=" + token).contains("HttpOnly"));
		assertThatThrownBy(() -> ssoController.start("oidc", null, invited.getId() + ".guessed", null,
				new MockHttpServletRequest(), new MockHttpServletResponse()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getMessageKey()).isEqualTo("error.user.inviteInvalid"));
		// A sign-in started without one drops what an abandoned one left in this browser.
		MockHttpServletResponse plain = new MockHttpServletResponse();
		ssoController.start("oidc", null, null, null, request, plain);
		assertThat(request.getSession().getAttribute(SsoController.SESSION_INVITE)).isNull();
		assertThat(plain.getHeaders("Set-Cookie")).anySatisfy(cookie ->
				assertThat(cookie).startsWith(SsoController.COOKIE_INVITE + "=;").contains("Max-Age=0"));
	}

	@Test
	@DisplayName("the SSO start keeps the app's challenge for the way back, and refuses a malformed one")
	void theStartKeepsTheChallenge() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/sso/start/oidc");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ssoController.start("oidc", null, null, CHALLENGE, request, response);

		assertThat(request.getSession().getAttribute(SsoController.SESSION_CHALLENGE)).isEqualTo(CHALLENGE);
		assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
				assertThat(cookie).startsWith(SsoController.COOKIE_CHALLENGE + "=" + CHALLENGE).contains("HttpOnly"));
		assertThatThrownBy(() -> ssoController.start("oidc", null, null, "not-a-challenge",
				new MockHttpServletRequest(), new MockHttpServletResponse()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getMessageKey()).isEqualTo("error.validationFailed"));
		// A sign-in started without one drops what an abandoned one left in this browser.
		MockHttpServletResponse plain = new MockHttpServletResponse();
		ssoController.start("oidc", null, null, null, request, plain);
		assertThat(request.getSession().getAttribute(SsoController.SESSION_CHALLENGE)).isNull();
		assertThat(plain.getHeaders("Set-Cookie")).anySatisfy(cookie ->
				assertThat(cookie).startsWith(SsoController.COOKIE_CHALLENGE + "=;").contains("Max-Age=0"));
	}

	// --- what the person can see about themselves --------------------------------

	@Test
	@DisplayName("the data export names the binding")
	@SuppressWarnings("unchecked")
	void theExportNamesTheBinding() {
		User grace = reload(sso(identity("grace"), "grace@example.org"));

		Map<String, Object> profile = (Map<String, Object>) me.exportData(grace).get("profile");

		assertThat((Map<String, Object>) profile.get("ssoLink"))
				.containsEntry("provider", "oidc")
				.containsEntry("issuer", ISSUER)
				.containsEntry("subject", "grace")
				.containsKey("linkedAt");
	}

	// --- helpers -----------------------------------------------------------------

	private SsoIdentity identity(String subject) {
		return new SsoIdentity("oidc", ISSUER, subject);
	}

	private User sso(SsoIdentity identity, String email) {
		return sso(identity, email, null);
	}

	private User sso(SsoIdentity identity, String email, Boolean emailVerified) {
		return accounts.signIn(identity,
				new SsoProfileMapper.SsoProfile(email, "Grace Hopper", null, emailVerified), User.Origin.OIDC, true)
				.user();
	}

	private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable signIn, SignInRefusal reason) {
		assertRefused(signIn, reason, reason);
	}

	/** Refused with [told] to the person and [recorded] in the audit log. */
	private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable signIn, SignInRefusal told,
			SignInRefusal recorded) {
		assertThatThrownBy(signIn).isInstanceOfSatisfying(SignInRefusedException.class, refused -> {
			assertThat(refused.refusal()).isEqualTo(told);
			assertThat(refused.recorded()).isEqualTo(recorded);
		});
	}

	/** A pending invitation to [email] as an administrator, opened by {@link #INVITE_SECRET}. */
	private User invitation(String email) {
		return users.save(User.builder().email(email).username(email.substring(0, email.indexOf('@')).replace('.', '-'))
				.displayName("Neu").roles(Set.of(Role.ADMIN, Role.MEMBER)).origin(User.Origin.LOCAL).active(false)
				.emailVerified(false).invitedAt(Instant.now())
				.inviteTokenHash(passwordEncoder.encode(INVITE_SECRET))
				.inviteExpiresAt(Instant.now().plusSeconds(86_400)).build());
	}

	private List<Notification> securityAlerts(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId())
				.and("type").is(Notification.Type.SECURITY_ALERT)), Notification.class);
	}

	/** SAML on, with its metadata from [location]. */
	private void samlFrom(String location) {
		ServerSettings current = settings.get();
		current.getSaml().setEnabled(true);
		current.getSaml().setIdpMetadataUri(location);
		settings.save(current);
	}

	private Authentication saml(String nameId, String email) {
		return new Saml2Authentication(new DefaultSaml2AuthenticatedPrincipal(nameId,
				Map.of("email", List.of(email), "displayName", List.of("Grace Hopper"))), "<Response/>", List.of());
	}

	private User local(String email) {
		return users.save(User.builder().email(email).username(email.substring(0, email.indexOf('@')))
				.displayName("Admin").passwordHash("$2a$04$notarealhashnotarealhashnotarealhashnotareal")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).origin(User.Origin.LOCAL).build());
	}

	private User account(String email, User.Origin origin, SsoLink link) {
		return users.save(User.builder().email(email)
				.username(email.substring(0, email.indexOf('@')).replace('.', '-'))
				.displayName("Grace").origin(origin).ssoLink(link).build());
	}

	private User reload(User user) {
		return users.findById(user.getId()).orElseThrow();
	}

	private void localSignIn(boolean enabled) {
		ServerSettings current = settings.get();
		current.getApp().setLocalAuthEnabled(enabled);
		settings.save(current);
	}

	private List<AuditLog> audits(AuditAction action) {
		return mongo.find(Query.query(Criteria.where("action").is(action)), AuditLog.class);
	}

	/** Runs [count] sign-ins at once; true for each that got in. */
	private List<Boolean> race(int count, IntFunction<SsoIdentity> identities, IntFunction<String> emails)
			throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> outcomes = new ArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < count; i++) {
				SsoIdentity identity = identities.apply(i);
				String email = emails.apply(i);
				outcomes.add(pool.submit(() -> {
					start.await();
					try {
						sso(identity, email);
						return true;
					}
					catch (SignInRefusedException refused) {
						return false;
					}
				}));
			}
			start.countDown();
			List<Boolean> entered = new ArrayList<>();
			for (Future<Boolean> outcome : outcomes) {
				entered.add(outcome.get());
			}
			return entered;
		}
	}

	private Authentication oidc(String subject, String email, Boolean emailVerified) {
		OidcIdToken.Builder token = OidcIdToken.withTokenValue("id-token")
				.subject(subject).issuer(ISSUER).claim("email", email)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
		if (emailVerified != null) {
			token.claim("email_verified", emailVerified);
		}
		return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), token.build()), List.of(),
				DynamicClientRegistrationRepository.OIDC_ID);
	}

	/** A plain OAuth2 user whose principal name is [nameAttribute]. */
	private Authentication oauth2(Map<String, Object> attributes, String nameAttribute) {
		return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attributes, nameAttribute),
				List.of(), DynamicClientRegistrationRepository.OAUTH2_ID);
	}

	private UriComponents signIn(Authentication authentication, boolean fromWeb) throws Exception {
		return signIn(authentication, fromWeb, null);
	}

	private UriComponents signIn(Authentication authentication, boolean fromWeb, String invite) throws Exception {
		return signIn(authentication, fromWeb, invite, null);
	}

	/** Through the real handler, for a flow started with [invite] and [challenge] (or none). */
	private UriComponents signIn(Authentication authentication, boolean fromWeb, String invite, String challenge)
			throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
		request.addPreferredLocale(Locale.GERMAN);
		List<Cookie> cookies = new ArrayList<>();
		if (fromWeb) {
			cookies.add(new Cookie(SsoController.COOKIE_RETURN_ORIGIN, WEB_APP));
		}
		if (invite != null) {
			cookies.add(new Cookie(SsoController.COOKIE_INVITE, invite));
		}
		if (challenge != null) {
			cookies.add(new Cookie(SsoController.COOKIE_CHALLENGE, challenge));
		}
		request.setCookies(cookies.toArray(Cookie[]::new));
		MockHttpServletResponse response = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(request, response, authentication);
		if (invite != null) {
			// Whatever the outcome, the way back drops the invitation.
			assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
					assertThat(cookie).startsWith(SsoController.COOKIE_INVITE + "=;"));
		}
		if (challenge != null) {
			assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
					assertThat(cookie).startsWith(SsoController.COOKIE_CHALLENGE + "=;"));
		}
		return UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
	}

	private void assertInvalidCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable exchange) {
		assertThatThrownBy(exchange).isInstanceOfSatisfying(ApiException.class,
				refused -> assertThat(refused.getMessageKey()).isEqualTo("error.sso.invalidCode"));
	}

	private String param(UriComponents uri, String name) {
		return uri.getQueryParams().getFirst(name);
	}
}
