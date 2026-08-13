package com.ahmadre.hinata.user;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.auth.sso.SsoProfileMapper;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

	private UserRepository users;
	private UserService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		SecurityPolicy securityPolicy = mock(SecurityPolicy.class);
		when(securityPolicy.passwordMinLength()).thenReturn(10);
		service = new UserService(users, new BCryptPasswordEncoder(4), mock(MongoTemplate.class),
				mock(AuditService.class), mock(NotificationService.class), securityPolicy);
	}

	@Test
	void rejectsShortPasswords() {
		assertThatThrownBy(() -> service.createLocal("a@b.de", "ada", "Ada", "short", Set.of(Role.MEMBER)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("passwordTooShort");
	}

	@Test
	void rejectsDuplicateEmail() {
		when(users.existsByEmailIgnoreCase("a@b.de")).thenReturn(true);
		assertThatThrownBy(() -> service.createLocal("a@b.de", "ada", "Ada", "long-enough-pass",
				Set.of(Role.MEMBER)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("emailInUse");
	}

	@Test
	void provisionsSsoUserWithUniqueUsername() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
		when(users.existsByUsernameIgnoreCase("grace")).thenReturn(true);
		when(users.existsByUsernameIgnoreCase("grace1")).thenReturn(false);

		User user = service.provisionSso(profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true);

		assertThat(user.getUsername()).isEqualTo("grace1");
		assertThat(user.getPasswordHash()).isNull();
		assertThat(user.getOrigin()).isEqualTo(User.Origin.OIDC);
		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void newSsoUserWithoutNameFallsBackToTheAddress() {
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

		User user = service.provisionSso(profile("it@example.org", null, null), User.Origin.OIDC, true);

		assertThat(user.getDisplayName()).isEqualTo("it@example.org");
		assertThat(user.getTitle()).isNull();
	}

	@Test
	void syncsProfileOfAnExistingSsoUserOnLogin() {
		when(users.findByEmailIgnoreCase(anyString()))
				.thenReturn(Optional.of(ssoUser("Grace H.", "Programmer")));

		User user = service.provisionSso(profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true);

		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void keepsLocalEditsWhenSyncIsOff() {
		when(users.findByEmailIgnoreCase(anyString()))
				.thenReturn(Optional.of(ssoUser("Grace H.", "Programmer")));

		User user = service.provisionSso(profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, false);

		assertThat(user.getDisplayName()).isEqualTo("Grace H.");
		assertThat(user.getTitle()).isEqualTo("Programmer");
	}

	@Test
	void healsADisplayNameThatIsJustTheAddressEvenWithoutSync() {
		when(users.findByEmailIgnoreCase(anyString()))
				.thenReturn(Optional.of(ssoUser("grace@example.org", null)));

		User user = service.provisionSso(profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, false);

		// The address was our own fallback, never the user's choice — and a blank
		// position is backfilled the same way.
		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void aMissingClaimNeverClearsAStoredValue() {
		when(users.findByEmailIgnoreCase(anyString()))
				.thenReturn(Optional.of(ssoUser("Grace Hopper", "Rear Admiral")));

		User user = service.provisionSso(profile("grace@example.org", null, null), User.Origin.OIDC, true);

		assertThat(user.getDisplayName()).isEqualTo("Grace Hopper");
		assertThat(user.getTitle()).isEqualTo("Rear Admiral");
	}

	@Test
	void neverRewritesTheProfileOfALocalAccount() {
		User local = User.builder().email("grace@example.org").username("grace")
				.displayName("grace@example.org").origin(User.Origin.LOCAL).build();
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(local));

		User user = service.provisionSso(profile("grace@example.org", "Grace Hopper", "Rear Admiral"),
				User.Origin.OIDC, true);

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
