package com.ahmadre.hinata.user;

/**
 * Cleans the free-text pronouns a user sets on themselves.
 *
 * <p>The field is deliberately free text — people name themselves, and a fixed
 * list would get that wrong. But it is now read back in plain-text contexts
 * that have their own punctuation: the audit trail prints
 * {@code "Alex (they/them) → Sam"}, the mention picker prints
 * {@code "@alex · they/them"}. Left unchecked, someone could set their pronouns
 * to {@code "x) → Platform Admin (y"} and forge a second actor into a line an
 * administrator is skimming for exactly that.
 *
 * <p>So two things are removed: characters that carry no meaning in a pronoun
 * but do carry meaning to a reader of those lines, and the invisible ones —
 * control codes and bidirectional overrides — that can reorder text on screen
 * into something other than what is stored.
 */
public final class Pronouns {

	private Pronouns() {
	}

	/** Separators our own plain-text lines are built from. */
	private static final String STRUCTURAL = "()[]{}·→<>|";

	/**
	 * The stored form of {@code raw}: trimmed, inner whitespace collapsed, and
	 * stripped of anything that could restructure a line it is printed into.
	 * Never null for a non-null input — an input that is nothing but noise
	 * becomes the empty string, which reads everywhere as "not set".
	 */
	public static String sanitize(String raw) {
		if (raw == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(raw.length());
		raw.codePoints().forEach(cp -> {
			if (isInvisible(cp) || STRUCTURAL.indexOf(cp) >= 0) {
				return;
			}
			// Any whitespace (including a tab or newline) becomes a plain space;
			// the collapse below then makes runs of it into one.
			out.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
		});
		return out.toString().replaceAll("\\s{2,}", " ").trim();
	}

	/**
	 * Control codes, and the bidi/format characters that can make rendered text
	 * disagree with stored text.
	 */
	private static boolean isInvisible(int cp) {
		int type = Character.getType(cp);
		return type == Character.CONTROL || type == Character.FORMAT
				|| type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
	}
}
