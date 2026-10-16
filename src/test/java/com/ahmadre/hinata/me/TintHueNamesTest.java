package com.ahmadre.hinata.me;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TintHueNamesTest {

	@Test
	void namesTheBrandHoneyAndThePaletteHues() {
		assertThat(TintHueNames.key(79)).isEqualTo("hue.honey");
		assertThat(TintHueNames.key(70)).isEqualTo("hue.honey");
		assertThat(TintHueNames.key(155)).isEqualTo("hue.green");
		assertThat(TintHueNames.key(250)).isEqualTo("hue.indigo");
		assertThat(TintHueNames.key(300)).isEqualTo("hue.violet");
		assertThat(TintHueNames.key(330)).isEqualTo("hue.pink");
	}

	@Test
	void namesEveryHueOnTheCircleByItsNearestFamily() {
		assertThat(TintHueNames.key(0)).isEqualTo("hue.red");
		assertThat(TintHueNames.key(359)).isEqualTo("hue.red");
		assertThat(TintHueNames.key(120)).isEqualTo("hue.green");
		assertThat(TintHueNames.key(230)).isEqualTo("hue.blue");
		for (int hue = 0; hue < 360; hue++) {
			assertThat(TintHueNames.key(hue)).startsWith("hue.");
		}
	}
}
