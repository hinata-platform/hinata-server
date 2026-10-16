package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.PasswordResetService;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.NotificationDays;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The machine-readable Art. 15 export carries every field the account holds. */
class MeServiceExportTest {

	@Test
	void exportContainsTintAndTimezone() {
		SessionService sessions = mock(SessionService.class);
		when(sessions.list("u-1")).thenReturn(List.of());
		TeamRepository teams = mock(TeamRepository.class);
		when(teams.findByMembersUserId("u-1")).thenReturn(List.of());
		ProjectService projects = mock(ProjectService.class);
		MeService me = new MeService(mock(UserRepository.class), mock(UserService.class),
				mock(PasswordEncoder.class), sessions, mock(TotpService.class),
				mock(RecoveryCodeService.class), mock(AccountMailService.class),
				mock(HinataProperties.class), teams, projects, mock(NotificationService.class),
				mock(GatewayService.class), mock(AuditService.class), mock(TokenService.class),
				mock(PasswordResetService.class), UserWordsFixture.real(), mock(NotificationDays.class),
				List.of());
		TintPreferences tint = new TintPreferences();
		tint.setStyle(TintPreferences.TintStyle.TINTED);
		tint.setHue(200);
		tint.setLevel(55);
		User user = User.builder().id("u-1").username("ada").email("ada@example.org")
				.timezone("Europe/Berlin").tint(tint).build();

		Map<String, Object> out = me.exportData(user);

		@SuppressWarnings("unchecked")
		Map<String, Object> profile = (Map<String, Object>) out.get("profile");
		assertThat(profile).containsEntry("timezone", "Europe/Berlin");
		assertThat(out.get("tint")).isInstanceOf(TintPreferences.class);
		assertThat(((TintPreferences) out.get("tint")).getHue()).isEqualTo(200);
	}
}
