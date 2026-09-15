package com.ahmadre.hinata.common;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * How long a text is as someone reads it, which is how the app's fields count it: a letter with its
 * accents, or an emoji made of several code points, is one character.
 */
public final class Characters {

	private Characters() {
	}

	/** How many characters [text] holds as someone reads them. */
	public static int count(String text) {
		BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);
		boundaries.setText(text);
		int characters = 0;
		while (boundaries.next() != BreakIterator.DONE) {
			characters++;
		}
		return characters;
	}
}
