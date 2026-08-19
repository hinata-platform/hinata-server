package com.ahmadre.hinata.common;

import java.util.Locale;

/**
 * A file size as a person reads it — "3.2 MB" rather than 3355443.
 *
 * <p>Shared rather than restated because two features hand the same number to a
 * reader: the single-issue export prints it in an attachment table, and the MCP
 * server puts it in the line that describes a file to a model. Two copies of the
 * rounding rule is two places for the same file to be 3.2 MB in one document and
 * 3.3 MB in the next.
 */
public final class ByteSize {

	/** Past gigabytes there is nothing an attachment can legitimately be. */
	private static final String[] UNITS = { "KB", "MB", "GB" };

	private ByteSize() {
	}

	/** [bytes] rounded to one decimal in the largest unit it fills. */
	public static String human(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < UNITS.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
	}
}
