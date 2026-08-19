package com.ahmadre.hinata.issue.export;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Where an export writes a value into something that will interpret it: a
 * spreadsheet cell, a download's file name, an XML document. All three are the
 * export's attack surface rather than its formatting, so they live together
 * where they can be reviewed together — and the one stamp every format dates
 * itself with sits here too, so four renderers cannot each pick a format.
 */
final class ExportText {

	/** UTC everywhere, as the rest of the platform stores and renders it. */
	static final DateTimeFormatter DATE_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

	/**
	 * Longest file-name stem an export produces, before the extension. Long
	 * enough for any real issue title, short enough that the whole name stays
	 * well inside the 255-byte limit every common file system imposes — a title
	 * is up to 300 characters and multi-byte ones cost more than one byte each.
	 */
	private static final int MAX_FILE_NAME_CHARS = 80;

	private ExportText() {
	}

	/**
	 * Neutralises a value that is about to become a spreadsheet cell.
	 *
	 * <p>A cell whose text begins with {@code =}, {@code +}, {@code -} or
	 * {@code @} is a <em>formula</em> to Excel, LibreOffice and Google Sheets —
	 * and a formula can call out to a URL or, with the right prefix, ask the user
	 * to run a command. The content here is an issue title and comments other
	 * people wrote, so the file is exactly the vehicle that attack needs: it
	 * arrives from a colleague, in a format nobody inspects before opening.
	 *
	 * <p>A leading apostrophe is the fix the spreadsheet applications themselves
	 * define: it makes the cell text and is not displayed. Tab, carriage return
	 * and newline are stripped from the front first, because a formula preceded
	 * by whitespace is still parsed as a formula.
	 */
	static String forSpreadsheet(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String text = value;
		while (!text.isEmpty() && (text.charAt(0) == '\t' || text.charAt(0) == '\r'
				|| text.charAt(0) == '\n')) {
			text = text.substring(1);
		}
		if (text.isEmpty()) {
			return "";
		}
		char first = text.charAt(0);
		return first == '=' || first == '+' || first == '-' || first == '@'
				? "'" + text : text;
	}

	/**
	 * The stem of the download's file name, built from an issue's readable id and
	 * title.
	 *
	 * <p>Everything that is not a letter, a digit or a dash goes, which settles
	 * three problems at once: the CR/LF that would let a title inject a second
	 * response header, the quotes that would end the {@code filename="…"} early,
	 * and the slashes and dots that would make the saved file land somewhere the
	 * downloader did not choose. The same shape the app's list export already
	 * uses for the PDF it shares, and the same rule the app repeats to name the
	 * file it saves — a byte download cannot read the header this ends up in.
	 *
	 * <p>Letters here means letters in any script, so a Cyrillic or German title
	 * keeps its own characters; Spring's {@code ContentDisposition} encodes them
	 * per RFC 5987 on the way out.
	 */
	static String fileNameStem(String readableId, String title) {
		String stem = (nz(readableId) + " " + nz(title)).trim();
		stem = stem.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
		if (stem.length() > MAX_FILE_NAME_CHARS) {
			stem = stem.substring(0, MAX_FILE_NAME_CHARS).replaceAll("-+$", "");
		}
		return stem.isEmpty() ? "issue" : stem;
	}

	/**
	 * Escapes a value for XML character data or an attribute.
	 *
	 * <p>Written by hand rather than delegated, because the document is written by
	 * hand: a title containing {@code <} or {@code &} would otherwise produce a
	 * file that is not XML at all, and the one containing {@code ]]>} would end a
	 * section it was never in. Characters XML 1.0 cannot represent at all — the
	 * control range, and unpaired surrogates a lone {@code char} can carry — are
	 * dropped rather than escaped, since there is no spelling for them that a
	 * parser will accept.
	 */
	static String forXml(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&apos;");
				default -> {
					if (isXmlSafe(value, i, c)) {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

	/** Whether [c] is a character XML 1.0 allows, surrogate pairing included. */
	private static boolean isXmlSafe(String value, int index, char c) {
		if (c == '\t' || c == '\n' || c == '\r') {
			return true;
		}
		if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == 0xFFFE || c == 0xFFFF) {
			return false;
		}
		if (Character.isHighSurrogate(c)) {
			return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
		}
		if (Character.isLowSurrogate(c)) {
			return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
		}
		return true;
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
