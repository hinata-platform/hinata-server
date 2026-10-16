package com.ahmadre.hinata.me;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TintPreferencesTest {

	@Test
	void defaultsAreStandardHoney() {
		TintPreferences defaults = TintPreferences.defaults();

		assertThat(defaults.getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
		assertThat(defaults.getHue()).isEqualTo(TintPreferences.DEFAULT_HUE);
		assertThat(defaults.getLevel()).isEqualTo(TintPreferences.DEFAULT_LEVEL);
	}

	@Test
	void sanitizedClampsHueAndLevel() {
		TintPreferences raw = new TintPreferences();
		raw.setHue(400);
		raw.setLevel(150);
		TintPreferences high = raw.sanitized();
		assertThat(high.getHue()).isEqualTo(359);
		assertThat(high.getLevel()).isEqualTo(100);

		raw.setHue(-5);
		raw.setLevel(-1);
		TintPreferences low = raw.sanitized();
		assertThat(low.getHue()).isZero();
		assertThat(low.getLevel()).isZero();
	}

	@Test
	void sanitizedKeepsHueZeroAndLevelZero() {
		TintPreferences raw = new TintPreferences();
		raw.setStyle("TINTED");
		raw.setHue(0);
		raw.setLevel(0);

		TintPreferences clean = raw.sanitized();

		assertThat(clean.getHue()).isZero();
		assertThat(clean.getLevel()).isZero();
	}

	@Test
	void sanitizedReplacesNullsWithDefaults() {
		TintPreferences clean = new TintPreferences().sanitized();

		assertThat(clean.getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
		assertThat(clean.getHue()).isEqualTo(TintPreferences.DEFAULT_HUE);
		assertThat(clean.getLevel()).isEqualTo(TintPreferences.DEFAULT_LEVEL);
	}

	@Test
	void unknownStoredStyleReadsAsDefault() {
		TintPreferences raw = new TintPreferences();
		raw.setStyle("GLASS");

		assertThat(raw.getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
		assertThat(raw.sanitized().getStyle()).isEqualTo(TintPreferences.TintStyle.DEFAULT);
	}
}
