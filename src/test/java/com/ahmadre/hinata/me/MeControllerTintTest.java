package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.issue.IssueWatchService;
import com.ahmadre.hinata.issue.export.ExportRateLimiter;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.ProfileChange;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tint on {@code /me} (HIN-150): a partial patch keeps what it does not
 * send, bad input is refused before anything is written, and an account that
 * predates the field, or holds a value this build does not know, still reads.
 */
class MeControllerTintTest {

	private static Validator validator;
	private MeService me;
	private CurrentUser currentUser;
	private MeController controller;

	@BeforeAll
	static void openValidator() {
		validator = Validation.buildDefaultValidatorFactory().getValidator();
	}

	@BeforeEach
	void setUp() {
		me = mock(MeService.class);
		currentUser = mock(CurrentUser.class);
		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);
		controller = new MeController(me, currentUser, mock(UserEvents.class),
				mock(DataExportPdfService.class), mock(ExportRateLimiter.class),
				mock(UserService.class),
				mock(IssueWatchService.class), mock(SecurityPolicy.class), mock(JwtDecoder.class),
				mock(SettingsService.class), mock(BrandLogoService.class), messages,
				mock(com.ahmadre.hinata.notification.NotificationDays.class));
	}

	private static TintPreferences stored(TintPreferences.TintStyle style, int hue, int level) {
		TintPreferences tint = new TintPreferences();
		tint.setStyle(style);
		tint.setHue(hue);
		tint.setLevel(level);
		return tint;
	}

	@Test
	void patchTintKeepsUnsentFields() {
		User user = User.builder().id("u-1")
				.tint(stored(TintPreferences.TintStyle.TINTED, 200, 30)).build();
		when(currentUser.require()).thenReturn(user);
		when(me.updateProfile(eq(user), any(ProfileChange.class))).thenAnswer(inv -> {
			ProfileChange change = inv.getArgument(1);
			user.setTint(change.tint());
			return user;
		});

		MeController.MeResponse response = controller.updateProfile(
				new MeController.UpdateProfileRequest(null, null, null, null, null, null,
						new MeController.TintRequest(null, 120, null)));

		assertThat(response.tint().getStyle()).isEqualTo(TintPreferences.TintStyle.TINTED);
		assertThat(response.tint().getHue()).isEqualTo(120);
		assertThat(response.tint().getLevel()).isEqualTo(30);
	}

	@Test
	void patchTintRejectsHue360() {
		var violations = validator.validate(new MeController.TintRequest(null, 360, null));

		assertThat(violations).hasSize(1);
		assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("hue");
	}

	@Test
	void patchTintRejectsUnknownStyle() {
		assertThatThrownBy(() -> new ObjectMapper().readValue(
				"{\"style\":\"PURPLE\",\"hue\":1,\"level\":1}", MeController.TintRequest.class))
				.isInstanceOf(InvalidFormatException.class);
	}

	@Test
	void getMeCarriesTintDefaultsWithoutField() {
		User user = User.builder().id("u-1").tint(null).build();

		MeController.MeResponse response = MeController.MeResponse.from(user);

		assertThat(response.tint()).isNotNull();
		assertThat(response.tint().getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
		assertThat(response.tint().getHue()).isEqualTo(TintPreferences.DEFAULT_HUE);
	}

	@Test
	void getMeSurvivesUnknownStoredStyle() {
		TintPreferences stored = new TintPreferences();
		stored.setStyle("GLASS");
		stored.setHue(500);
		User user = User.builder().id("u-1").tint(stored).build();

		MeController.MeResponse response = MeController.MeResponse.from(user);

		assertThat(response.tint().getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
		assertThat(response.tint().getHue()).isEqualTo(359);
	}

	@Test
	void tintSerialisesStyleAsItsName() throws Exception {
		String json = new ObjectMapper().writeValueAsString(stored(TintPreferences.TintStyle.TINTED, 5, 6));

		assertThat(json).contains("\"style\":\"TINTED\"").contains("\"hue\":5").contains("\"level\":6");
	}
}
