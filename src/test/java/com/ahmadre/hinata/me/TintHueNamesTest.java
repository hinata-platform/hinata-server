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
	void namesEveryHueOnTheCircleByItsFamilyBand() {
		assertThat(TintHueNames.key(0)).isEqualTo("hue.red");
		assertThat(TintHueNames.key(359)).isEqualTo("hue.red");
		assertThat(TintHueNames.key(120)).isEqualTo("hue.green");
		assertThat(TintHueNames.key(230)).isEqualTo("hue.blue");
		for (int hue = 0; hue < 360; hue++) {
			assertThat(TintHueNames.key(hue)).startsWith("hue.");
		}
	}

	/**
	 * The one table both sides cut the circle with. The app's
	 * {@code hue_labels_test.dart} asserts the same literal; change both or neither.
	 */
	@Test
	void bandsMatchTheAppsTable() {
		int[] ends = {12, 25, 37, 60, 90, 157, 185, 205, 237, 262, 310, 347};
		String[] keys = {"red", "coral", "orange", "amber", "honey", "green", "teal", "cyan", "blue",
				"indigo", "violet", "pink"};
		int start = 0;
		for (int i = 0; i < ends.length; i++) {
			for (int hue = start; hue <= ends[i]; hue++) {
				assertThat(TintHueNames.key(hue)).as("hue %d", hue).isEqualTo("hue." + keys[i]);
			}
			start = ends[i] + 1;
		}
		for (int hue = start; hue < 360; hue++) {
			assertThat(TintHueNames.key(hue)).isEqualTo("hue.red");
		}
	}
}
