package com.ahmadre.hinata.user;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
				mock(org.springframework.context.ApplicationEventPublisher.class),
				mock(AuditService.class), mock(NotificationService.class), securityPolicy,
				com.ahmadre.hinata.common.UserWordsFixture.real());
	}

	@Test
	void rejectsShortPasswords() {
		assertThatThrownBy(() -> service.createLocal("a@b.de", "ada", "Ada", "short", Set.of(Role.MEMBER)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("passwordTooShort");
	}

	@Test
	void rejectsPasswordsLongerThanBcryptReads() {
		// BCrypt reads 72 bytes and refuses to hash more; a longer password was a 500.
		assertThatThrownBy(() -> service.createLocal("a@b.de", "ada", "Ada", "x".repeat(73), Set.of(Role.MEMBER)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("passwordTooLong");
		// Bytes count, not characters: 37 umlauts are 74 bytes.
		assertThatThrownBy(() -> service.validatePassword("ä".repeat(37)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("passwordTooLong");
		assertThat(service.createLocal("a@b.de", "ada", "Ada", "x".repeat(72), Set.of(Role.MEMBER))).isNotNull();
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
	void aUsernameIsMadeFromTheAddressAndNeverLeftEmpty() {
		assertThat(service.uniqueUsernameFrom("grace.hopper@example.org")).isEqualTo("grace.hopper");
		assertThat(service.uniqueUsernameFrom("jürgen@example.org")).isEqualTo("jrgen");
		// Nothing a username can hold is left of this one.
		assertThat(service.uniqueUsernameFrom("ü@example.org")).isEqualTo("user");
	}
}
