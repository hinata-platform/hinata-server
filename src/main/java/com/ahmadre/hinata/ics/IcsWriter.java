package com.ahmadre.hinata.ics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a calendar as iCalendar text (RFC 5545), the counterpart of {@link IcsParser}.
 *
 * <p>Built for feeds a calendar app subscribes to: one VCALENDAR of timed events, each written in
 * the zone it was planned in ({@code DTSTART;TZID=Europe/Berlin:…}) so a subscriber in another zone
 * still sees "09:00 in Berlin". Every zone used gets its VTIMEZONE, made from {@code java.time}
 * rather than ical4j (see the package documentation): the transitions that fall into the window of
 * the events, each as its own observance, so no subscriber has to know the zone's rules.
 *
 * <p>Text from people (summary, location, description, the calendar's name) is escaped in a fixed
 * order: backslash first, then semicolon and comma, then line breaks (CRLF, CR and LF each become
 * {@code \n}); every other C0 control character and DEL is dropped. A title cannot start a property
 * or a component of its own. Lines are folded at 75 octets, never inside a UTF-8 sequence.
 *
 * <p>Needs no application context and keeps no state.
 */
public final class IcsWriter {

	/** Longest line in octets, without the CRLF (RFC 5545 section 3.1). */
	static final int LINE_OCTETS = 75;

	private static final String CRLF = "\r\n";
	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
	private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	/** Observances written per zone at most: a window of months has a handful. */
	private static final int TRANSITIONS_MAX = 64;

	private IcsWriter() {
	}

	/** What a subscriber is told about the event. Null means "not said". */
	public enum Status {
		TENTATIVE, CONFIRMED, CANCELLED
	}

	/**
	 * A calendar to write.
	 *
	 * @param productId the {@code PRODID}, e.g. {@code -//Hinata//Shifts//EN}
	 * @param name      shown as the calendar's name (X-WR-CALNAME and NAME); null for none
	 * @param stamp     when the file was made (DTSTAMP of every event)
	 * @param refresh   how often a subscriber should fetch again; null for no hint
	 */
	public record Calendar(String productId, String name, Instant stamp, Duration refresh, List<Event> events) {
	}

	/**
	 * One timed event.
	 *
	 * @param uid  stable across fetches, so a subscriber updates instead of duplicating
	 * @param zone the zone the event was planned in; UTC writes times in UTC
	 */
	public record Event(String uid, Instant start, Instant end, ZoneId zone, String summary, String location,
			String description, String url, Status status) {
	}

	/** The calendar as UTF-8 bytes, lines ending in CRLF. */
	public static byte[] bytes(Calendar calendar) {
		return write(calendar).getBytes(StandardCharsets.UTF_8);
	}

	public static String write(Calendar calendar) {
		StringBuilder out = new StringBuilder(512 + calendar.events().size() * 384);
		line(out, "BEGIN:VCALENDAR");
		line(out, "VERSION:2.0");
		line(out, "PRODID:" + escape(calendar.productId()));
		line(out, "CALSCALE:GREGORIAN");
		line(out, "METHOD:PUBLISH");
		if (present(calendar.name())) {
			line(out, "NAME:" + escape(calendar.name()));
			line(out, "X-WR-CALNAME:" + escape(calendar.name()));
		}
		if (calendar.refresh() != null) {
			line(out, "REFRESH-INTERVAL;VALUE=DURATION:" + calendar.refresh());
			line(out, "X-PUBLISHED-TTL:" + calendar.refresh());
		}
		for (Map.Entry<ZoneId, Instant[]> zone : zones(calendar.events()).entrySet()) {
			timezone(out, zone.getKey(), zone.getValue()[0], zone.getValue()[1]);
		}
		String stamp = UTC.format(calendar.stamp());
		for (Event event : calendar.events()) {
			line(out, "BEGIN:VEVENT");
			line(out, "UID:" + escape(event.uid()));
			line(out, "DTSTAMP:" + stamp);
			line(out, "DTSTART" + time(event.start(), event.zone()));
			line(out, "DTEND" + time(event.end(), event.zone()));
			line(out, "SUMMARY:" + escape(event.summary() == null ? "" : event.summary()));
			if (present(event.location())) {
				line(out, "LOCATION:" + escape(event.location()));
			}
			if (present(event.description())) {
				line(out, "DESCRIPTION:" + escape(event.description()));
			}
			if (present(event.url())) {
				// A URI, not TEXT: nothing to escape, but no control character either.
				line(out, "URL:" + strip(event.url()));
			}
			if (event.status() != null) {
				line(out, "STATUS:" + event.status().name());
			}
			line(out, "TRANSP:OPAQUE");
			line(out, "END:VEVENT");
		}
		line(out, "END:VCALENDAR");
		return out.toString();
	}

	/**
	 * TEXT as RFC 5545 section 3.3.11 has it: backslash, then semicolon and comma, then every line
	 * break as {@code \n}. Other control characters are dropped.
	 */
	static String escape(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case ';' -> out.append("\\;");
				case ',' -> out.append("\\,");
				case '\r' -> {
					out.append("\\n");
					if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
						i++;
					}
				}
				case '\n' -> out.append("\\n");
				default -> {
					if (!control(c)) {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}

	/** Folds [content] at 75 octets on code point boundaries and ends it with CRLF. */
	static void line(StringBuilder out, String content) {
		int octets = 0;
		for (int i = 0; i < content.length(); ) {
			int codePoint = content.codePointAt(i);
			int size = utf8Length(codePoint);
			if (octets + size > LINE_OCTETS) {
				out.append(CRLF).append(' ');
				octets = 1;
			}
			out.appendCodePoint(codePoint);
			octets += size;
			i += Character.charCount(codePoint);
		}
		out.append(CRLF);
	}

	/**
	 * {@code ;TZID=…:local} in the event's zone, or {@code :…Z} in UTC. UTC also where a local time
	 * cannot say which instant it means: the second pass through the hour a clock goes back, which
	 * RFC 5545 reads as the first.
	 */
	static String time(Instant instant, ZoneId zone) {
		if (utc(zone)) {
			return ":" + UTC.format(instant);
		}
		ZoneRules rules = zone.getRules();
		LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
		List<ZoneOffset> valid = rules.getValidOffsets(local);
		if (valid.size() > 1 && !valid.get(0).equals(rules.getOffset(instant))) {
			return ":" + UTC.format(instant);
		}
		return ";TZID=" + zone.getId() + ":" + LOCAL.format(local);
	}

	/** The zones the events are written in, each with the earliest start and latest end. */
	private static Map<ZoneId, Instant[]> zones(List<Event> events) {
		Map<ZoneId, Instant[]> zones = new LinkedHashMap<>();
		for (Event event : events) {
			if (utc(event.zone())) {
				continue;
			}
			Instant[] span = zones.computeIfAbsent(event.zone(), zone -> new Instant[]{event.start(), event.end()});
			if (event.start().isBefore(span[0])) {
				span[0] = event.start();
			}
			if (event.end().isAfter(span[1])) {
				span[1] = event.end();
			}
		}
		return zones;
	}

	/**
	 * The zone as observances: the one in force at [from] and every change up to [to], each with
	 * the local time it happens at (in the offset before it) and the offsets on both sides.
	 */
	private static void timezone(StringBuilder out, ZoneId zone, Instant from, Instant to) {
		ZoneRules rules = zone.getRules();
		List<ZoneOffsetTransition> transitions = new ArrayList<>();
		ZoneOffsetTransition previous = rules.previousTransition(from);
		if (previous != null) {
			transitions.add(previous);
		}
		ZoneOffsetTransition next = rules.nextTransition(previous != null ? previous.getInstant() : from);
		while (next != null && !next.getInstant().isAfter(to) && transitions.size() < TRANSITIONS_MAX) {
			transitions.add(next);
			next = rules.nextTransition(next.getInstant());
		}
		line(out, "BEGIN:VTIMEZONE");
		line(out, "TZID:" + zone.getId());
		if (transitions.isEmpty()) {
			ZoneOffset offset = rules.getOffset(from);
			line(out, "BEGIN:STANDARD");
			line(out, "DTSTART:19700101T000000");
			line(out, "TZOFFSETFROM:" + offset(offset));
			line(out, "TZOFFSETTO:" + offset(offset));
			line(out, "END:STANDARD");
		}
		for (ZoneOffsetTransition transition : transitions) {
			String kind = rules.isDaylightSavings(transition.getInstant()) ? "DAYLIGHT" : "STANDARD";
			line(out, "BEGIN:" + kind);
			line(out, "DTSTART:" + LOCAL.format(transition.getDateTimeBefore()));
			line(out, "TZOFFSETFROM:" + offset(transition.getOffsetBefore()));
			line(out, "TZOFFSETTO:" + offset(transition.getOffsetAfter()));
			line(out, "END:" + kind);
		}
		line(out, "END:VTIMEZONE");
	}

	/** {@code +0100}, {@code -0330}, seconds only when there are any. */
	static String offset(ZoneOffset offset) {
		int total = offset.getTotalSeconds();
		int abs = Math.abs(total);
		String text = String.format("%s%02d%02d", total < 0 ? "-" : "+", abs / 3600, (abs / 60) % 60);
		return abs % 60 == 0 ? text : text + String.format("%02d", abs % 60);
	}

	/**
	 * Written in UTC: UTC itself, fixed offsets, and any id that could not stand in a TZID
	 * parameter unquoted. IANA ids never contain those characters.
	 */
	private static boolean utc(ZoneId zone) {
		if (zone == null || zone instanceof ZoneOffset || zone.getRules().isFixedOffset()
				&& zone.getRules().getOffset(Instant.EPOCH).equals(ZoneOffset.UTC)) {
			return true;
		}
		String id = zone.getId();
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (c == ';' || c == ':' || c == ',' || c == '"' || control(c)) {
				return true;
			}
		}
		return false;
	}

	private static String strip(String value) {
		StringBuilder out = new StringBuilder(value.length());
		value.chars().filter(c -> !control((char) c)).forEach(c -> out.append((char) c));
		return out.toString();
	}

	private static boolean control(char c) {
		return c < 0x20 || c == 0x7F;
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	private static int utf8Length(int codePoint) {
		if (codePoint < 0x80) {
			return 1;
		}
		if (codePoint < 0x800) {
			return 2;
		}
		return codePoint < 0x10000 ? 3 : 4;
	}
}
