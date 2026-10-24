package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.ClientIpResolver;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.user.Suspension;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A reset link sets a new password, but signs in only who may sign in: a person suspended after
 * asking for the link gets the refusal a sign-in gets, and no session (HIN-172).
 */
class PasswordResetControllerTest {

	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder encoder = mock(PasswordEncoder.class);
	private final AuthService auth = mock(AuthService.class);
	private final SessionService sessions = mock(SessionService.class);
	@SuppressWarnings("unchecked")
	private final PasswordResetController controller = new PasswordResetController(users, mock(UserService.class),
			encoder, auth, sessions, new ClientIpResolver(), mock(PasswordResetService.class),
			mock(ObjectProvider.class));

	@Test
	void aSuspendedPersonIsNotSignedInByAResetLink() {
		User suspended = User.builder().id("u1").username("karl").email("karl@example.org")
				.origin(User.Origin.LOCAL).emailVerified(true).active(false)
				.suspension(new Suspension(Instant.now().plusSeconds(3600), "c1", "m1", Instant.now()))
				.passwordResetTokenHash("hash").passwordResetExpiresAt(Instant.now().plusSeconds(600)).build();
		when(users.findById("u1")).thenReturn(Optional.of(suspended));
		when(encoder.matches(anyString(), anyString())).thenReturn(true);
		when(encoder.encode(anyString())).thenReturn("new");
		when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		assertThatThrownBy(() -> controller.accept(new PasswordResetController.AcceptRequest("u1.secret",
				"ein neues langes Passwort"), new MockHttpServletRequest()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getDetails()).containsEntry("code", "accountSuspended"));
		verify(auth, never()).issueWithSession(any(), any(), any());
	}
}
