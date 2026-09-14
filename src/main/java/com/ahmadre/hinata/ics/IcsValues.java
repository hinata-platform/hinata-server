package com.ahmadre.hinata.ics;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value types of RFC 5545 that the parser reads, read strictly and by hand.
 *
 * <p>Every method throws {@link DateTimeException} for a value it cannot read. The
 * parser turns that into an {@link IcsParseException} carrying the line, and
 * nothing of the value itself.
 */
final class IcsValues {

	/** Longer than any duration or offset a calendar writes; checked before the pattern runs. */
	private static final int MAX_SHORT_VALUE = 32;

	private static final Pattern DURATION = Pattern.compile(
			"([+-])?P(?:(\\d{1,6})W|(\\d{1,6}D)?(?:T(\\d{1,6}H)?(\\d{1,6}M)?(\\d{1,6}S)?)?)");

	private static final Pattern OFFSET = Pattern.compile("([+-])(\\d{2})(\\d{2})(\\d{2})?");

	private IcsValues() {
	}

	/** A DATE: {@code yyyyMMdd}. */
	static LocalDate date(String value) {
		String text = value.strip();
		if (text.length() != 8 || !digits(text)) {
			throw new DateTimeException("not a DATE");
		}
		return LocalDate.of(number(text, 0, 4), number(text, 4, 6), number(text, 6, 8));
	}

	/** A DATE-TIME: {@code yyyyMMdd'T'HHmmss}, with or without the Z that {@link #isUtc} reports. */
	static LocalDateTime dateTime(String value) {
		String text = value.strip();
		if (isUtc(text)) {
			text = text.substring(0, text.length() - 1);
		}
		if (text.length() != 15 || Character.toUpperCase(text.charAt(8)) != 'T'
				|| !digits(text.substring(0, 8)) || !digits(text.substring(9))) {
			throw new DateTimeException("not a DATE-TIME");
		}
		// A leap second is read as the second before it; java.time has no 60.
		int second = Math.min(59, number(text, 13, 15));
		return LocalDateTime.of(date(text.substring(0, 8)), LocalTime.of(number(text, 9, 11), number(text, 11, 13), second));
	}

	static boolean isUtc(String value) {
		String text = value.strip();
		return text.endsWith("Z") || text.endsWith("z");
	}

	/**
	 * A duration as RFC 5545 3.3.6 writes it. Days and weeks stay days, because a day
	 * in a zone with summer time is not always 24 hours; the caller adds them on the
	 * calendar and the rest on the clock.
	 */
	static Span duration(String value) {
		String text = value.strip();
		Matcher matcher = DURATION.matcher(text);
		if (text.length() > MAX_SHORT_VALUE || !matcher.matches() || text.endsWith("P") || text.endsWith("T")) {
			throw new DateTimeException("not a DURATION");
		}
		long days = matcher.group(2) != null ? 7L * Long.parseLong(matcher.group(2)) : amount(matcher.group(3));
		long seconds = amount(matcher.group(4)) * 3600 + amount(matcher.group(5)) * 60 + amount(matcher.group(6));
		int sign = "-".equals(matcher.group(1)) ? -1 : 1;
		return new Span(sign * days, sign * seconds);
	}

	/** A UTC offset as TZOFFSETFROM and TZOFFSETTO write it: {@code +0100}, {@code -0500}, {@code +053045}. */
	static ZoneOffset offset(String value) {
		String text = value.strip();
		Matcher matcher = OFFSET.matcher(text);
		if (text.length() > MAX_SHORT_VALUE || !matcher.matches()) {
			throw new DateTimeException("not a UTC offset");
		}
		int sign = "-".equals(matcher.group(1)) ? -1 : 1;
		int seconds = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
		return ZoneOffset.ofHoursMinutesSeconds(sign * Integer.parseInt(matcher.group(2)),
				sign * Integer.parseInt(matcher.group(3)), sign * seconds);
	}

	/**
	 * A TEXT value with its escapes undone, cut to [max] characters and trimmed; null
	 * when nothing is left. Exchange writes a lone line break into most descriptions.
	 */
	static String text(String value, int max) {
		if (value == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(Math.min(value.length(), max));
		for (int i = 0; i < value.length() && out.length() < max; i++) {
			char c = value.charAt(i);
			if (c == '\\' && i + 1 < value.length()) {
				char escaped = value.charAt(++i);
				out.append(escaped == 'n' || escaped == 'N' ? '\n' : escaped);
			}
			else {
				out.append(c);
			}
		}
		if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
			out.setLength(out.length() - 1);
		}
		String text = out.toString().strip();
		return text.isEmpty() ? null : text;
	}

	/** Days on the calendar and seconds on the clock, both signed. */
	record Span(long days, long seconds) {
	}

	private static long amount(String group) {
		return group == null ? 0 : Long.parseLong(group.substring(0, group.length() - 1));
	}

	private static boolean digits(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) < '0' || text.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	private static int number(String text, int from, int to) {
		return Integer.parseInt(text, from, to, 10);
	}
}
