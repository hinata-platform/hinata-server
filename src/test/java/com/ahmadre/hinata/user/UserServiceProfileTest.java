package com.ahmadre.hinata.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Patching a profile: the one implementation behind both {@code /api/v1/me} and
 * {@code /api/v1/users/me}.
 *
 * <p>{@code pronouns} is a free-text field like {@code title} — trimmed and
 * saved when present, left untouched when the request omits it. The time zone
 * is the one value that can be refused, and it is checked before anything is
 * written, so a refusal cannot leave the caller's user half-changed.
 */
class UserServiceProfileTest {

	private UserRepository users;
	private UserService userService;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
		userService = new UserService(users, mock(PasswordEncoder.class),
				mock(MongoTemplate.class),
				mock(org.springframework.context.ApplicationEventPublisher.class),
				mock(AuditService.class),
				mock(NotificationService.class), mock(SecurityPolicy.class),
				UserWordsFixture.real());
	}

	@Test
	void updateProfile_trimsAndSavesPronouns() {
		User user = User.builder().id("u-1").build();

		User saved = userService.updateProfile(user, null, null, "  she/her  ", null, null, null);

		assertThat(saved.getPronouns()).isEqualTo("she/her");
	}

	@Test
	void updateProfile_storesAKnownTimezone() {
		User user = User.builder().id("u-1").build();

		User saved = userService.updateProfile(user, null, null, null, null, " Europe/Berlin ", null);

		assertThat(saved.getTimezone()).isEqualTo("Europe/Berlin");
	}

	@Test
	void updateProfile_rejectsAnUnknownTimezoneBeforeSavingAnything() {
		User user = User.builder().id("u-1").timezone("Europe/Berlin").build();

		assertThatThrownBy(() -> userService.updateProfile(user, "New Name", null, null, null, "Mars/Olympus", null))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(((ApiException) thrown).getMessageKey()).isEqualTo("error.user.invalidTimezone");
				});
		verify(users, never()).save(any());
		assertThat(user.getTimezone()).isEqualTo("Europe/Berlin");
	}

	@Test
	void updateProfile_blankClearsTheTimezoneAndAbsentKeepsIt() {
		User user = User.builder().id("u-1").timezone("Europe/Berlin").build();

		assertThat(userService.updateProfile(user, null, null, null, null, null, null).getTimezone())
				.isEqualTo("Europe/Berlin");
		assertThat(userService.updateProfile(user, null, null, null, null, "  ", null).getTimezone()).isNull();
	}

	@Test
	void updateProfile_leavesPronounsUntouchedWhenOmitted() {
		User user = User.builder().id("u-1").pronouns("they/them").build();

		User saved = userService.updateProfile(user, "New Name", null, null, null, null, null);

		assertThat(saved.getPronouns()).isEqualTo("they/them");
	}
}
