package com.ahmadre.hinata.me;

/**
 * The message key naming a tint hue in words, for the Art. 15 report: a reader
 * is owed "Honey (79°)", not a number alone (Art. 12 Abs. 1 DSGVO). The circle
 * is cut into bands, one per family, so every degree has a name — the same
 * bands as the app's {@code hueFamilyKey}, so the report and the settings screen
 * agree on what to call a colour. The bands follow OKLCH, where sRGB red sits
 * at 29°, orange at 53° and magenta at 328°. {@code TintHueNamesTest} and the app's
 * {@code hue_labels_test.dart} assert one shared table of band ends, so the
 * two copies cannot drift.
 */
public final class TintHueNames {

	/** Upper bound (inclusive) of each family's band, walking the circle from red. */
	private static final int[] BAND_ENDS = {11, 37, 62, 74, 105, 170, 190, 235, 270, 290, 315, 359};
	private static final String[] KEYS = {"hue.pink", "hue.red", "hue.orange", "hue.amber",
			"hue.honey", "hue.green", "hue.teal", "hue.cyan", "hue.blue", "hue.indigo",
			"hue.violet", "hue.pink"};

	private TintHueNames() {
	}

	public static String key(int hue) {
		int degrees = Math.floorMod(hue, 360);
		for (int i = 0; i < BAND_ENDS.length; i++) {
			if (degrees <= BAND_ENDS[i]) {
				return KEYS[i];
			}
		}
		return KEYS[0];
	}
}
