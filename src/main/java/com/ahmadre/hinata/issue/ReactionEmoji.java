package com.ahmadre.hinata.issue;

import java.text.BreakIterator;

/**
 * What a reaction may be (HIN-176): exactly one emoji, as the eye sees one. A flag, a family or a
 * thumb with a skin tone is several code points and still one; a word, a digit or two emoji are
 * not. The picker only offers emoji, so this guards the API alone.
 */
final class ReactionEmoji {

	/** Longest emoji kept, in UTF-16 units; the longest family sequences stay below it. */
	static final int MAX_LENGTH = 32;

	private static final int KEYCAP = 0x20E3;
	private static final int REGIONAL_INDICATOR_A = 0x1F1E6;
	private static final int REGIONAL_INDICATOR_Z = 0x1F1FF;

	private ReactionEmoji() {
	}

	static boolean isSingle(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
			return false;
		}
		BreakIterator graphemes = BreakIterator.getCharacterInstance();
		graphemes.setText(value);
		if (graphemes.next() != value.length()) {
			return false;
		}
		int first = value.codePointAt(0);
		if (first >= REGIONAL_INDICATOR_A && first <= REGIONAL_INDICATOR_Z) {
			// A flag is two regional indicators; one alone is a letter in a box.
			return value.codePointCount(0, value.length()) == 2;
		}
		// A keycap is a digit, # or * followed by the keycap mark; the digit alone is no emoji.
		if (value.codePoints().anyMatch(cp -> cp == KEYCAP)) {
			return true;
		}
		return Character.isExtendedPictographic(first) || Character.isEmojiPresentation(first);
	}
}
