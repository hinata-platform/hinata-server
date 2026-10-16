package com.ahmadre.hinata.user;

import com.ahmadre.hinata.me.TimePreferences;
import com.ahmadre.hinata.me.TintPreferences;

/**
 * What a profile patch asks to change. A field left {@code null} is left alone.
 *
 * <p>A record rather than seven positional parameters, which is what
 * {@link UserService#updateProfile} took until the tint (HIN-150) would have
 * made it eight: callers that set one value no longer write six {@code null}s
 * and the test for a time zone no longer has to count commas.
 */
public record ProfileChange(String displayName, String title, String pronouns, String locale,
		String timezone, TimePreferences timePreferences, TintPreferences tint) {

	public static ProfileChange of() {
		return new ProfileChange(null, null, null, null, null, null, null);
	}

	public ProfileChange displayName(String value) {
		return new ProfileChange(value, title, pronouns, locale, timezone, timePreferences, tint);
	}

	public ProfileChange title(String value) {
		return new ProfileChange(displayName, value, pronouns, locale, timezone, timePreferences, tint);
	}

	public ProfileChange pronouns(String value) {
		return new ProfileChange(displayName, title, value, locale, timezone, timePreferences, tint);
	}

	public ProfileChange locale(String value) {
		return new ProfileChange(displayName, title, pronouns, value, timezone, timePreferences, tint);
	}

	public ProfileChange timezone(String value) {
		return new ProfileChange(displayName, title, pronouns, locale, value, timePreferences, tint);
	}

	public ProfileChange timePreferences(TimePreferences value) {
		return new ProfileChange(displayName, title, pronouns, locale, timezone, value, tint);
	}

	public ProfileChange tint(TintPreferences value) {
		return new ProfileChange(displayName, title, pronouns, locale, timezone, timePreferences, value);
	}
}
