package com.ahmadre.hinata.issue.export;

import org.springframework.context.MessageSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Everything an export prints that is not content: the labels on its fields, the
 * headings above its sections, and every date and time in it.
 *
 * <p>It exists because an export is the one document this platform produces that
 * a person reads on paper, in their own language, in their own country — and
 * until now it was written entirely in English and stamped entirely in UTC. A
 * German reader was handed "Created / 2026-08-18 16:39 UTC", which is two
 * things they have to translate and one they have to do arithmetic on.
 *
 * <p>Both answers come from the request, not from the server:
 *
 * <ul>
 *   <li>The <b>language</b> is the resolved request locale — the app already
 *       sends {@code Accept-Language} for the language the user picked in it,
 *       which is what {@code LocaleConfig} resolves. So an export is in the
 *       language of the app that asked for it, not the language of the host.</li>
 *   <li>The <b>time zone</b> is the caller's, sent as {@code ?tz=}. It falls
 *       back to the organization's configured zone and only then to UTC. The
 *       server's own zone is deliberately never consulted: it is fixed to UTC
 *       on purpose ({@code HinataServerApplication}) so that stored instants are
 *       deterministic, and that is a storage decision that has no business
 *       reaching a document somebody reads.</li>
 * </ul>
 *
 * <p>One instance per export, built by {@link IssueExportController} and carried
 * on {@link IssueExport} so the four renderers reach the same words and the same
 * clock without each resolving anything.
 */
public final class ExportWords {

	private final MessageSource messages;
	private final Locale locale;
	private final ZoneId zone;
	private final DateTimeFormatter timestamp;
	private final DateTimeFormatter day;

	public ExportWords(MessageSource messages, Locale locale, ZoneId zone) {
		this.messages = messages;
		this.locale = locale;
		this.zone = zone;
		// Localized medium date + short time, then the zone in words. The zone is
		// part of the value, not decoration: the same document is read by people
		// in different places, and "23:50" without it is only a time for whoever
		// exported it. Appended through the formatter rather than taken from
		// ZoneId so it follows daylight saving — an instant in January prints
		// CET where one in August prints CEST.
		this.timestamp = new DateTimeFormatterBuilder()
				.appendLocalized(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.appendLiteral(' ')
				.appendZoneText(TextStyle.SHORT)
				.toFormatter(locale)
				.withZone(zone);
		// Date-only values carry no time and no zone — a due date is the same day
		// wherever it is read, and shifting one across zones is how a deadline
		// moves by a day. Formatted for the locale, never converted.
		this.day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
				.withLocale(locale);
	}

	/**
	 * The message for [key] in this export's language.
	 *
	 * <p>A missing key renders as the key itself rather than throwing: an export
	 * is a document, and a document with an untranslated label in it is a
	 * blemish, while a 500 in the middle of a download is a broken feature. The
	 * key is visible enough that the gap gets fixed.
	 */
	public String t(String key, Object... args) {
		return messages.getMessage(key, args, key, locale);
	}

	/**
	 * The message for [key], or [fallback] when the bundle has no such key.
	 *
	 * <p>For values that come out of an enum. A type or a priority added in the
	 * server without a matching entry in both bundles should read as its own name
	 * — "SUBTASK" is poor, and "export.type.SUBTASK" is worse.
	 */
	public String or(String key, String fallback) {
		return messages.getMessage(key, null, fallback, locale);
	}

	/** An instant, in the reader's zone and the reader's language. */
	public String instant(Instant value) {
		return value == null ? "" : timestamp.format(value);
	}

	/**
	 * An instant as ISO-8601 with the reader's offset — {@code
	 * 2026-08-27T01:50:00+02:00}.
	 *
	 * <p>For the XML export, which a program reads. The reader's zone still
	 * applies (the offset says which), but the shape is one a parser accepts
	 * rather than one a person does; a localized "27.08.2026, 01:50 MESZ" in an
	 * attribute is a value nothing downstream can use.
	 */
	public String isoInstant(Instant value) {
		return value == null ? "" : DateTimeFormatter.ISO_OFFSET_DATE_TIME
				.format(value.atZone(zone));
	}

	/** A date-only value, in the reader's language and unshifted. */
	public String date(LocalDate value) {
		return value == null ? "" : day.format(value);
	}

	public Locale locale() {
		return locale;
	}

	public ZoneId zone() {
		return zone;
	}
}
