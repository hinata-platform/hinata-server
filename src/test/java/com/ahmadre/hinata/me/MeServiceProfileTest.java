package com.ahmadre.hinata.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
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

		User saved = me.updateProfile(user, null, null, "  she/her  ", null);

		assertThat(saved.getPronouns()).isEqualTo("she/her");
	}

	@Test
	void updateProfile_leavesPronounsUntouchedWhenOmitted() {
		User user = User.builder().id("u-1").pronouns("they/them").build();

		User saved = me.updateProfile(user, "New Name", null, null, null);

		assertThat(saved.getPronouns()).isEqualTo("they/them");
	}
}
