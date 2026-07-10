package com.ahmadre.hinata.user;

import com.ahmadre.hinata.audit.AuditService;
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
		service = new UserService(users, new BCryptPasswordEncoder(4), mock(MongoTemplate.class),
				mock(AuditService.class), mock(NotificationService.class));
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

		User user = service.provisionSso("grace@example.org", "Grace Hopper", User.Origin.OIDC);

		assertThat(user.getUsername()).isEqualTo("grace1");
		assertThat(user.getPasswordHash()).isNull();
		assertThat(user.getOrigin()).isEqualTo(User.Origin.OIDC);
	}
}
