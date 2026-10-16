package com.ahmadre.hinata.me;

/**
 * The message key naming a tint hue in words, for the Art. 15 report: a reader
 * is owed "Honey (79°)", not a number alone (Art. 12 Abs. 1 DSGVO). Same
 * families and anchors as the app's {@code hueLabel}, so the report and the
 * settings screen agree on what to call a colour; the circle is cut into bands,
 * one per family, so every degree has a name.
 */
public final class TintHueNames {

	/** Upper bound (inclusive) of each family's band, walking the circle from red. */
	private static final int[] BAND_ENDS = {12, 25, 37, 60, 90, 157, 185, 205, 237, 262, 310, 347};
	private static final String[] KEYS = {"hue.red", "hue.coral", "hue.orange", "hue.amber",
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
