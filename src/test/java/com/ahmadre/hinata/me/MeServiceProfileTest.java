package com.ahmadre.hinata.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.auth.PasswordResetService;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@code pronouns} is a free-text profile field like {@code title}: only
 * trimmed and saved when present, left untouched when the request omits it.
 */
class MeServiceProfileTest {

	private UserRepository users;
	private MeService me;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
		me = new MeService(users, mock(UserService.class), mock(PasswordEncoder.class),
				mock(SessionService.class), mock(TotpService.class), mock(RecoveryCodeService.class),
				mock(AccountMailService.class), mock(HinataProperties.class), mock(TeamRepository.class),
				mock(ProjectService.class), mock(NotificationService.class), mock(GatewayService.class),
				mock(AuditService.class), mock(TokenService.class), mock(PasswordResetService.class),
				com.ahmadre.hinata.common.UserWordsFixture.real());
	}

	@Test
	void updateProfile_trimsAndSavesPronouns() {
		User user = User.builder().id("u-1").build();

		User saved = me.updateProfile(user, null, null, "  she/her  ", null, null);

		assertThat(saved.getPronouns()).isEqualTo("she/her");
	}

	@Test
	void updateProfile_storesAKnownTimezone() {
		User user = User.builder().id("u-1").build();

		User saved = me.updateProfile(user, null, null, null, null, " Europe/Berlin ");

		assertThat(saved.getTimezone()).isEqualTo("Europe/Berlin");
	}

	@Test
	void updateProfile_rejectsAnUnknownTimezoneBeforeSavingAnything() {
		User user = User.builder().id("u-1").timezone("Europe/Berlin").build();

		assertThatThrownBy(() -> me.updateProfile(user, "New Name", null, null, null, "Mars/Olympus"))
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

		assertThat(me.updateProfile(user, null, null, null, null, null).getTimezone())
				.isEqualTo("Europe/Berlin");
		assertThat(me.updateProfile(user, null, null, null, null, "  ").getTimezone()).isNull();
	}

	@Test
	void updateProfile_leavesPronounsUntouchedWhenOmitted() {
		User user = User.builder().id("u-1").pronouns("they/them").build();

		User saved = me.updateProfile(user, "New Name", null, null, null, null);

		assertThat(saved.getPronouns()).isEqualTo("they/them");
	}
}
