package com.ahmadre.hinata.ics;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.ahmadre.hinata.ics.IcsParseException.Reason.MALFORMED;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.NOT_A_CALENDAR;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.TOO_MANY_COMPONENTS;

/**
 * Reads the bytes of a calendar into components and properties, and does nothing else.
 *
 * <p>Every limit applies while reading, before anything is built from what was read.
 * What the parser has no use for is read past without keeping any of it: a VALARM, a
 * VTODO, an X-property, an ATTACH, every parameter but VALUE and TZID, and a second
 * SUMMARY or DTSTART in the same event. None of that can fill memory or fail the
 * calendar. Lines may end in CRLF or in LF alone; both occur in real feeds.
 *
 * <p>Some limits cut and some refuse, and whoever shows the result needs to know which:
 *
 * <ul>
 * <li><b>Cut</b>, and the result says so: events after the first {@code maxEvents},
 * time zone definitions after the first {@value #MAX_TIMEZONES}, and observances after
 * the first {@value #MAX_OBSERVANCES} in one definition.</li>
 * <li><b>Refused</b> with an {@link IcsParseException}: more than
 * {@link #MAX_COMPONENTS} components of any kind, nesting deeper than
 * {@link #MAX_DEPTH}, an unfolded line longer than {@link #MAX_LINE} that the parser would
 * read, more than {@link #MAX_REPEATED} RDATE or EXDATE lines in one event or
 * {@link #MAX_REPEATED_IN_CALENDAR} in the whole calendar, and a structure that does not
 * close. An RDATE or EXDATE without a value is read past and counts for neither.</li>
 * </ul>
 */
final class IcsLexer {

	static final int MAX_BYTES = 2 * 1024 * 1024;

	/** An unfolded line; longer ones fail the calendar only when they are a property the parser reads. */
	static final int MAX_LINE = 128 * 1024;

	/** VCALENDAR, VEVENT, VALARM is three; two more for vendor components. */
	static final int MAX_DEPTH = 5;

	/** Components of any kind, kept or not. */
	static final int MAX_COMPONENTS = 20_000;

	/** RDATE and EXDATE lines in one event; the parser allows as many values. */
	static final int MAX_REPEATED = 10_000;

	/** RDATE and EXDATE lines in the whole calendar. */
	static final int MAX_REPEATED_IN_CALENDAR = 20_000;

	private static final int MAX_NAME = 64;
	private static final int MAX_TIMEZONES = 100;
	private static final int MAX_OBSERVANCES = 50;

	/** Some exporters put one in front of the calendar; it is not part of the first line. */
	private static final char BYTE_ORDER_MARK = 0xFEFF;

	/** The properties kept, by component. A component that is not here is read past. */
	private static final Map<String, Set<String>> KEEP = Map.of(
			"VCALENDAR", Set.of("X-WR-CALNAME"),
			"VEVENT", Set.of("UID", "DTSTART", "DTEND", "DURATION", "RRULE", "RDATE", "EXDATE", "RECURRENCE-ID",
					"SUMMARY", "LOCATION", "DESCRIPTION", "STATUS", "TRANSP"),
			"VTIMEZONE", Set.of("TZID"),
			"STANDARD", Set.of("DTSTART", "TZOFFSETFROM", "TZOFFSETTO", "RRULE"),
			"DAYLIGHT", Set.of("DTSTART", "TZOFFSETFROM", "TZOFFSETTO", "RRULE"));

	/** The properties a component may hold more than once; of every other one only the first is kept. */
	private static final Set<String> REPEATED = Set.of("RDATE", "EXDATE");

	/** The only parameters the parser reads. */
	private static final Set<String> PARAMETERS = Set.of("VALUE", "TZID");

	private IcsLexer() {
	}

	/** A property as written: its name upper-cased, VALUE and TZID without quotes, the value untouched. */
	record Property(String name, Map<String, String> parameters, String value, int line) {

		String parameter(String parameter) {
			return parameters.get(parameter);
		}
	}

	/** A component with what was kept of it. */
	static final class Component {

		final String name;
		final List<Property> properties = new ArrayList<>();
		final List<Component> children = new ArrayList<>();

		/** How often each kept name occurred, the ones read past included; an RDATE or EXDATE without a value does not count. */
		private final Map<String, Integer> occurrences = new HashMap<>();

		Component(String name) {
			this.name = name;
		}

		Property first(String property) {
			for (Property candidate : properties) {
				if (candidate.name().equals(property)) {
					return candidate;
				}
			}
			return null;
		}

		List<Property> all(String property) {
			return properties.stream().filter(candidate -> candidate.name().equals(property)).toList();
		}

		List<Component> children(String component) {
			return children.stream().filter(child -> child.name.equals(component)).toList();
		}
	}

	/** The one calendar in the file, and whether events were left out because there were too many. */
	record Result(Component calendar, boolean truncated) {
	}

	/** Reads the first VCALENDAR of [bytes]; anything after it is ignored. */
	static Result read(byte[] bytes, int maxEvents) {
		return new Reader(new String(bytes, StandardCharsets.UTF_8), maxEvents).read();
	}

	private record Frame(String name, Component kept) {
	}

	private static final class Reader {

		private final String text;
		private final int maxEvents;
		private final Deque<Frame> open = new ArrayDeque<>();
		private int position;
		private int physical;
		private int line;
		private boolean overlong;
		private int components;
		private int events;
		private int timezones;
		private int repeatedLines;
		private boolean truncated;

		Reader(String text, int maxEvents) {
			this.text = text;
			this.maxEvents = maxEvents;
			this.position = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
		}

		Result read() {
			String first = next();
			if (first == null || overlong || !"BEGIN".equals(nameOf(first)) || !"VCALENDAR".equals(componentOf(first))) {
				throw new IcsParseException(NOT_A_CALENDAR, 0);
			}
			Component calendar = new Component("VCALENDAR");
			components = 1;
			open.push(new Frame("VCALENDAR", calendar));
			String content;
			while ((content = next()) != null) {
				String name = nameOf(content);
				if (name.equals("BEGIN") || name.equals("END")) {
					String component = componentOf(content);
					if (component == null || overlong) {
						throw malformed();
					}
					if (name.equals("BEGIN")) {
						begin(component);
					}
					else if (end(component)) {
						return new Result(calendar, truncated);
					}
				}
				else {
					keep(name, content);
				}
			}
			throw new IcsParseException(MALFORMED, physical);
		}

		private void begin(String component) {
			if (open.size() >= MAX_DEPTH) {
				throw malformed();
			}
			if (++components > MAX_COMPONENTS) {
				throw new IcsParseException(TOO_MANY_COMPONENTS, line);
			}
			Frame parent = open.peek();
			Component kept = parent.kept() != null && admitted(parent, component) ? new Component(component) : null;
			if (kept != null) {
				parent.kept().children.add(kept);
			}
			open.push(new Frame(component, kept));
		}

		private boolean admitted(Frame parent, String component) {
			switch (parent.name() + ">" + component) {
				case "VCALENDAR>VEVENT":
					if (events == maxEvents) {
						truncated = true;
						return false;
					}
					events++;
					return true;
				case "VCALENDAR>VTIMEZONE":
					return underCap(timezones++ < MAX_TIMEZONES);
				case "VTIMEZONE>STANDARD", "VTIMEZONE>DAYLIGHT":
					return underCap(parent.kept().children.size() < MAX_OBSERVANCES);
				default:
					return false;
			}
		}

		/** [admitted] as it is; when a cap turned the component away, the result is marked as cut. */
		private boolean underCap(boolean admitted) {
			if (!admitted) {
				truncated = true;
			}
			return admitted;
		}

		/** Closes [component]; true when that was the calendar itself. */
		private boolean end(String component) {
			if (!open.pop().name().equals(component)) {
				throw malformed();
			}
			return open.isEmpty();
		}

		private void keep(String name, String content) {
			Frame current = open.peek();
			Set<String> wanted = current.kept() == null ? null : KEEP.get(current.name());
			if (wanted == null || !wanted.contains(name)) {
				return;
			}
			boolean repeated = REPEATED.contains(name);
			if (!repeated && current.kept().occurrences.merge(name, 1, Integer::sum) > 1) {
				return;
			}
			if (overlong) {
				throw malformed();
			}
			Property property = property(name, content);
			if (repeated) {
				// An empty RDATE or EXDATE says nothing, so it is neither kept nor counted.
				if (property.value().isBlank()) {
					return;
				}
				if (current.kept().occurrences.merge(name, 1, Integer::sum) > MAX_REPEATED
						|| ++repeatedLines > MAX_REPEATED_IN_CALENDAR) {
					throw malformed();
				}
			}
			current.kept().properties.add(property);
		}

		/** NAME *(";" PARAM "=" VALUE *("," VALUE)) ":" VALUE, parameter values quoted or not. */
		private Property property(String name, String content) {
			int at = name.length();
			Map<String, String> parameters = new HashMap<>(4);
			while (at < content.length() && content.charAt(at) == ';') {
				int nameStart = ++at;
				while (at < content.length() && isNameChar(content.charAt(at))) {
					at++;
				}
				if (at == nameStart || at >= content.length() || content.charAt(at) != '=') {
					throw malformed();
				}
				String parameter = content.substring(nameStart, at).toUpperCase(Locale.ROOT);
				StringBuilder value = PARAMETERS.contains(parameter) && !parameters.containsKey(parameter)
						? new StringBuilder() : null;
				do {
					at++;
					if (at < content.length() && content.charAt(at) == '"') {
						int close = content.indexOf('"', at + 1);
						if (close < 0) {
							throw malformed();
						}
						if (value != null) {
							value.append(content, at + 1, close);
						}
						at = close + 1;
					}
					else {
						int start = at;
						while (at < content.length() && ";:,\"".indexOf(content.charAt(at)) < 0) {
							at++;
						}
						if (value != null) {
							value.append(content, start, at);
						}
					}
					if (value != null && at < content.length() && content.charAt(at) == ',') {
						value.append(',');
					}
				} while (at < content.length() && content.charAt(at) == ',');
				if (value != null) {
					parameters.put(parameter, value.toString());
				}
			}
			if (at >= content.length() || content.charAt(at) != ':') {
				throw malformed();
			}
			return new Property(name, Map.copyOf(parameters), content.substring(at + 1), line);
		}

		/** The next unfolded, non-empty line or null at the end; {@link #line} is the line it began on. */
		private String next() {
			while (position < text.length()) {
				StringBuilder content = new StringBuilder();
				overlong = false;
				line = ++physical;
				position = append(content, position);
				while (position < text.length() && (text.charAt(position) == ' ' || text.charAt(position) == '\t')) {
					physical++;
					position = append(content, position + 1);
				}
				if (!content.isEmpty() || overlong) {
					return content.toString();
				}
			}
			return null;
		}

		/** Appends the physical line at [from], up to the cap, and returns where the next one starts. */
		private int append(StringBuilder content, int from) {
			int newline = text.indexOf('\n', from);
			int end = newline < 0 ? text.length() : newline;
			int stop = end > from && text.charAt(end - 1) == '\r' ? end - 1 : end;
			int room = MAX_LINE - content.length();
			if (stop - from > room) {
				content.append(text, from, from + Math.max(room, 0));
				overlong = true;
			}
			else {
				content.append(text, from, stop);
			}
			return newline < 0 ? text.length() : newline + 1;
		}

		private IcsParseException malformed() {
			return new IcsParseException(MALFORMED, line);
		}
	}

	private static String nameOf(String content) {
		int end = 0;
		while (end < content.length() && end <= MAX_NAME && isNameChar(content.charAt(end))) {
			end++;
		}
		return content.substring(0, end).toUpperCase(Locale.ROOT);
	}

	/** The component a BEGIN or END line names, or null when the line is not one. */
	private static String componentOf(String content) {
		int colon = content.indexOf(':');
		if (colon != 3 && colon != 5) {
			return null;
		}
		String name = content.substring(colon + 1).strip().toUpperCase(Locale.ROOT);
		if (name.isEmpty() || name.length() > MAX_NAME) {
			return null;
		}
		for (int i = 0; i < name.length(); i++) {
			if (!isNameChar(name.charAt(i))) {
				return null;
			}
		}
		return name;
	}

	private static boolean isNameChar(char c) {
		return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-';
	}
}
