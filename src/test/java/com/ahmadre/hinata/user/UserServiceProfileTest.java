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
	private MongoTemplate mongo;
	private UserService userService;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
		mongo = mock(MongoTemplate.class);
		userService = new UserService(users, mock(PasswordEncoder.class),
				mongo,
				mock(org.springframework.context.ApplicationEventPublisher.class),
				mock(AuditService.class),
				mock(NotificationService.class), mock(SecurityPolicy.class),
				UserWordsFixture.real());
	}

	@Test
	void updateProfile_trimsAndSavesPronouns() {
		User user = User.builder().id("u-1").build();

		User saved = userService.updateProfile(user, ProfileChange.of().pronouns("  she/her  "));

		assertThat(saved.getPronouns()).isEqualTo("she/her");
	}

	@Test
	void updateProfile_storesAKnownTimezone() {
		User user = User.builder().id("u-1").build();

		User saved = userService.updateProfile(user, ProfileChange.of().timezone(" Europe/Berlin "));

		assertThat(saved.getTimezone()).isEqualTo("Europe/Berlin");
	}

	@Test
	void updateProfile_rejectsAnUnknownTimezoneBeforeSavingAnything() {
		User user = User.builder().id("u-1").timezone("Europe/Berlin").build();

		assertThatThrownBy(() -> userService.updateProfile(user, ProfileChange.of().displayName("New Name").timezone("Mars/Olympus")))
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

		assertThat(userService.updateProfile(user, ProfileChange.of()).getTimezone())
				.isEqualTo("Europe/Berlin");
		assertThat(userService.updateProfile(user, ProfileChange.of().timezone("  ")).getTimezone()).isNull();
	}

	@Test
	void updateProfile_leavesPronounsUntouchedWhenOmitted() {
		User user = User.builder().id("u-1").pronouns("they/them").build();

		User saved = userService.updateProfile(user, ProfileChange.of().displayName("New Name"));

		assertThat(saved.getPronouns()).isEqualTo("they/them");
	}

	@Test
	void updateProfile_tintOnlyPatchNeverSavesTheWholeDocument() {
		User user = User.builder().id("u-1").displayName("Old").build();
		com.ahmadre.hinata.me.TintPreferences tint = new com.ahmadre.hinata.me.TintPreferences();
		tint.setStyle(com.ahmadre.hinata.me.TintPreferences.TintStyle.TINTED);
		tint.setHue(120);
		tint.setLevel(40);

		User saved = userService.updateProfile(user, ProfileChange.of().tint(tint));

		verify(users, never()).save(any());
		org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> update =
				org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
		verify(mongo).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
				update.capture(), org.mockito.ArgumentMatchers.eq(User.class));
		assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class).keySet())
				.containsExactly("tint");
		assertThat(saved.getTint().getHue()).isEqualTo(120);
		assertThat(saved.getTint().getLevel()).isEqualTo(40);
	}

	@Test
	void updateProfile_profileFieldsStillSaveWithoutTouchingTint() {
		User user = User.builder().id("u-1").build();

		userService.updateProfile(user, ProfileChange.of().displayName("New Name"));

		verify(users).save(any());
		verify(mongo, never()).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
				any(org.springframework.data.mongodb.core.query.Update.class),
				org.mockito.ArgumentMatchers.eq(User.class));
	}
}
