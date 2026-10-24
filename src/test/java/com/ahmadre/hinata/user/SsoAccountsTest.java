package com.ahmadre.hinata.user;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.AccountAdmission;
import com.ahmadre.hinata.auth.AuthPolicy;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.auth.SignInRefusal;
import com.ahmadre.hinata.auth.SignInRefusedException;
import com.ahmadre.hinata.auth.sso.SsoProfileMapper;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.setup.SettingsService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The profile side of an SSO sign-in: what a new account is made of, and what a later sign-in
 * brings up to date. The binding itself is tested against a real MongoDB in
 * {@code SsoAccountBindingIntegrationTest}.
 */
class SsoAccountsTest {

	private static final String ISSUER = "https://idp.example.org";
	private static final SsoIdentity GRACE = new SsoIdentity("oidc", ISSUER, "grace");

	private UserRepository users;
	private MongoTemplate mongo;
	private AccountAdmission admission;
	private SsoAccounts accounts;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		UserService userService = new UserService(users, new BCryptPasswordEncoder(4), mock(MongoTemplate.class),
				mock(org.springframework.context.ApplicationEventPublisher.class),
				mock(AuditService.class), mock(NotificationService.class), mock(SecurityPolicy.class),
				UserWordsFixture.real(),
				com.ahmadre.hinata.richtext.TestTextChecks.allowAll());
		mongo = mock(MongoTemplate.class);
		admission = mock(AccountAdmission.class);
		accounts = new SsoAccounts(users, mongo, userService, mock(AuthPolicy.class), admission,
				mock(SettingsService.class), mock(AuditService.class), mock(NotificationService.class),
				UserWordsFixture.real(),
				com.ahmadre.hinata.richtext.TestTextChecks.allowAll());
	}

	/** A provider that asks for approval makes the first sign-in wait, as a registration does (HIN-175). */
	@Test
	void aFirstSignInWaitsForApprovalWhereTheProviderAsksForIt() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
		when(users.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
		when(admission.firstSignInWaits("oidc", "grace@example.org")).thenReturn(true);

		assertThatThrownBy(() -> accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", null),
				User.Origin.OIDC, true))
				.isInstanceOfSatisfying(SignInRefusedException.class,
						refused -> assertThat(refused.refusal()).isEqualTo(SignInRefusal.PENDING_APPROVAL));

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		assertThat(saved.getValue().isActive()).isFalse();
		assertThat(saved.getValue().isAwaitingApproval()).isTrue();
		verify(admission).requestApproval(saved.getValue());
	}

	@Test
	void provisionsSsoUserWithUniqueUsername() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
		when(users.existsByUsernameIgnoreCase("grace")).thenReturn(true);
		when(users.existsByUsernameIgnoreCase("grace1")).thenReturn(false);

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true).user();

		assertThat(user.getUsername()).isEqualTo("grace1");
		assertThat(user.getPasswordHash()).isNull();
		assertThat(user.getOrigin()).isEqualTo(User.Origin.OIDC);
		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	/** A refused name from the provider is replaced, the sign-in goes on (HIN-176). */
	@Test
	void aRefusedNameFromTheProviderIsReplacedNotRefused() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
		SsoAccounts checked = new SsoAccounts(users, mongo, new UserService(users, new BCryptPasswordEncoder(4),
				mock(MongoTemplate.class), mock(org.springframework.context.ApplicationEventPublisher.class),
				mock(AuditService.class), mock(NotificationService.class), mock(SecurityPolicy.class),
				UserWordsFixture.real(), com.ahmadre.hinata.richtext.TestTextChecks.allowAll()),
				mock(AuthPolicy.class), admission, mock(SettingsService.class), mock(AuditService.class),
				mock(NotificationService.class), UserWordsFixture.real(),
				com.ahmadre.hinata.richtext.TestTextChecks.refusing("kys"));

		User user = checked.signIn(GRACE, profile("kys@example.org", "kys", "kys"), User.Origin.OIDC, true).user();

		assertThat(user.getUsername()).matches("user-[0-9a-f]{6}");
		assertThat(user.getDisplayName()).isEqualTo(user.getUsername());
		assertThat(user.getTitle()).isNull();
	}

	@Test
	void newSsoUserWithoutNameFallsBackToTheAddress() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

		User user = accounts.signIn(GRACE, profile("it@example.org", null, null), User.Origin.OIDC, true).user();

		assertThat(user.getDisplayName()).isEqualTo("it@example.org");
		assertThat(user.getTitle()).isNull();
	}

	@Test
	void aProviderThatSendsNoAddressMakesNoAccount() {
		// The mapper puts the principal's name where the address would be. As an address in
		// Hinata it would stand in front of the username a password sign-in looks up after it.
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accounts.signIn(GRACE, profile("admin", null, null), User.Origin.OIDC, true))
				.isInstanceOfSatisfying(SignInRefusedException.class,
						refused -> assertThat(refused.refusal()).isEqualTo(SignInRefusal.SSO_NO_EMAIL));
		verify(users, never()).save(any(User.class));
	}

	@Test
	void anAccountBoundAlreadyNeedsNoAddress() {
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(ssoUser("Grace Hopper", null)));

		assertThat(accounts.signIn(GRACE, profile("grace", null, null), User.Origin.OIDC, true).user().getEmail())
				.isEqualTo("grace@example.org");
	}

	@Test
	void aProfileSyncWritesTheTwoFieldsAndNothingElse() {
		// A save of the whole account as read at the start of the sign-in would put back what an
		// administrator changed meanwhile, an account switched off included.
		User grace = ssoUser("Grace H.", "Programmer");
		grace.setId("u-grace");
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(grace));

		accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"), User.Origin.OIDC, true);

		ArgumentCaptor<Update> written = ArgumentCaptor.forClass(Update.class);
		verify(mongo).updateFirst(any(Query.class), written.capture(), eq(User.class));
		assertThat(((Document) written.getValue().getUpdateObject().get("$set")).keySet())
				.containsExactlyInAnyOrder("displayName", "title", "updatedAt");
		verify(users, never()).save(any(User.class));
	}

	@Test
	void syncsProfileOfAnExistingSsoUserOnLogin() {
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(ssoUser("Grace H.", "Programmer")));

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true).user();

		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void wordsModerationRemovedAreNotBroughtBackBySigningIn() {
		User removed = ssoUser("Person 4f2a", null);
		removed.setModeration(new HashMap<>(Map.of("profile", ContentMark.removed("case-1"))));
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(removed));

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true).user();

		assertThat(user.getDisplayName()).isEqualTo("Person 4f2a");
		assertThat(user.getTitle()).isNull();
		verify(mongo, never()).updateFirst(any(), any(), eq(User.class));
	}

	@Test
	void keepsLocalEditsWhenSyncIsOff() {
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(ssoUser("Grace H.", "Programmer")));

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, false).user();

		assertThat(user.getDisplayName()).isEqualTo("Grace H.");
		assertThat(user.getTitle()).isEqualTo("Programmer");
	}

	@Test
	void healsADisplayNameThatIsJustTheAddressEvenWithoutSync() {
		when(users.findBySsoLink("oidc", ISSUER, "grace"))
				.thenReturn(Optional.of(ssoUser("grace@example.org", null)));

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, false).user();

		// The address was our own fallback, never the user's choice — and a blank
		// position is backfilled the same way.
		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void aMissingClaimNeverClearsAStoredValue() {
		when(users.findBySsoLink("oidc", ISSUER, "grace"))
				.thenReturn(Optional.of(ssoUser("Grace Hopper", "Rear Admiral")));

		User user = accounts.signIn(GRACE, profile("grace@example.org", null, null), User.Origin.OIDC, true).user();

		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void neverRewritesTheProfileOfALocalAccount() {
		// A local account an SSO sign-in may enter (bound while local sign-in was off) keeps the
		// profile its owner gave it.
		User local = User.builder().email("grace@example.org").username("grace")
				.displayName("grace@example.org").origin(User.Origin.LOCAL).build();
		when(users.findBySsoLink("oidc", ISSUER, "grace")).thenReturn(Optional.of(local));

		User user = accounts.signIn(GRACE, profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true).user();

		assertThat(user.getDisplayName()).isEqualTo("grace@example.org");
		assertThat(user.getTitle()).isNull();
	}

	private SsoProfileMapper.SsoProfile profile(String email, String displayName, String title) {
		return new SsoProfileMapper.SsoProfile(email, displayName, title);
	}

	private User ssoUser(String displayName, String title) {
		return User.builder().email("grace@example.org").username("grace")
				.displayName(displayName).title(title).origin(User.Origin.OIDC).build();
	}
}
