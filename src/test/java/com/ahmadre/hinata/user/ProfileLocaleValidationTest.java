package com.ahmadre.hinata.user;

import com.ahmadre.hinata.config.LocaleConfig;
import com.ahmadre.hinata.me.MeController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile endpoints accept a locale by regex. That regex used to list five
 * languages while the server spoke nine, so a Japanese, French, Russian or
 * Arabic user could not save the language they were using. Pinned here to the
 * resolver's list, in both places the request record exists.
 */
class ProfileLocaleValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void thePatternListsExactlyTheLanguagesTheServerSpeaks() {
		assertThat(Arrays.asList(LocaleConfig.LANGUAGE_PATTERN.split("\\|")))
				.containsExactlyInAnyOrderElementsOf(LocaleConfig.supportedLanguages())
				.hasSize(9);
	}

	@Test
	void everySupportedLanguageIsAcceptedByBothProfileRequests() {
		for (String language : LocaleConfig.supportedLanguages()) {
			assertThat(validator.validate(new MeController.UpdateProfileRequest(null, null, null,
					language, null))).as("/me accepts %s", language).isEmpty();
			assertThat(validator.validate(new UserController.UpdateProfileRequest(null, null, null,
					language, null))).as("/users/me accepts %s", language).isEmpty();
		}
	}

	@Test
	void anUnknownLanguageIsRejectedByBoth() {
		Set<ConstraintViolation<MeController.UpdateProfileRequest>> me = validator.validate(
				new MeController.UpdateProfileRequest(null, null, null, "xx", null));
		Set<ConstraintViolation<UserController.UpdateProfileRequest>> users = validator.validate(
				new UserController.UpdateProfileRequest(null, null, null, "xx", null));

		assertThat(me).extracting(v -> v.getPropertyPath().toString()).containsExactly("locale");
		assertThat(users).extracting(v -> v.getPropertyPath().toString()).containsExactly("locale");
	}

	@Test
	void anOverlongTimezoneNeverReachesTheService() {
		String tooLong = "x".repeat(UserZones.MAX_LENGTH + 1);

		assertThat(validator.validate(new MeController.UpdateProfileRequest(null, null, null, null,
				tooLong))).extracting(v -> v.getPropertyPath().toString()).containsExactly("timezone");
		assertThat(validator.validate(new MeController.UpdateProfileRequest(null, null, null, null,
				"Europe/Berlin"))).isEmpty();
	}
}
