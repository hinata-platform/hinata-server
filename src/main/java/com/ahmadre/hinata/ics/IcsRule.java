package com.ahmadre.hinata.ics;

import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An RRULE checked part by part before ical4j's recurrence engine sees it.
 *
 * <p>The engine is trusted with the arithmetic of RFC 5545, not with input. Measured
 * against ical4j 4.3.0: a series without COUNT jumps to the window by doubling an
 * int, which overflows and then never returns once the series starts more than 2³⁰
 * steps before the window (every second since 1990 is enough); a series with COUNT
 * is walked from its first occurrence; and every BY list multiplies the candidates
 * the engine builds for each period. So:
 *
 * <ul>
 * <li>only DAILY, WEEKLY, MONTHLY and YEARLY are expanded, where no calendar year
 * between 1 and 9999 is 2³⁰ steps away;</li>
 * <li>COUNT is cut at {@link #MAX_COUNT}, INTERVAL above {@link #MAX_INTERVAL} and
 * more than {@link #MAX_CANDIDATES_PER_PERIOD} candidates per period are not
 * expanded;</li>
 * <li>UNTIL is left out of what the engine gets, because it would compare a UTC
 * value with wall-clock candidates; the parser applies it to instants itself.</li>
 * </ul>
 *
 * <p>A rule that breaks RFC 5545 is refused with a {@link DateTimeException}; a
 * valid rule the engine is not given is still read, and the parser says the
 * calendar was cut.
 *
 * @param forRecur   the rule as the engine gets it: only the parts checked here, COUNT cut, no UNTIL
 * @param expandable whether the engine may expand it
 * @param shortened  COUNT was larger than {@link #MAX_COUNT}
 */
record IcsRule(String frequency, Integer count, String until, String forRecur, boolean expandable, boolean shortened) {

	static final int MAX_COUNT = 5_000;
	static final int MAX_INTERVAL = 100;
	static final int MAX_CANDIDATES_PER_PERIOD = 1_000;

	private static final int MAX_LENGTH = 1_024;
	private static final int MAX_LIST = 366;
	private static final Set<String> FREQUENCIES =
			Set.of("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY");
	private static final Set<String> BELOW_A_DAY = Set.of("SECONDLY", "MINUTELY", "HOURLY");
	private static final Set<String> WEEK_DAYS = Set.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");
	private static final Pattern WEEKDAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
	private static final Pattern NUMBER = Pattern.compile("[+-]?\\d{1,10}");

	/** Checks [value]; [days] says the series is one of whole days, where times of day have no place. */
	static IcsRule read(String value, boolean days) {
		if (value.length() > MAX_LENGTH) {
			throw invalid();
		}
		Map<String, String> parts = new LinkedHashMap<>();
		for (String part : value.strip().split(";")) {
			if (part.isBlank()) {
				continue;
			}
			int equals = part.indexOf('=');
			if (equals <= 0) {
				throw invalid();
			}
			String name = part.substring(0, equals).strip().toUpperCase(Locale.ROOT);
			if (parts.put(name, part.substring(equals + 1).strip().toUpperCase(Locale.ROOT)) != null) {
				throw invalid();
			}
		}
		String frequency = parts.remove("FREQ");
		if (frequency == null || !FREQUENCIES.contains(frequency)) {
			throw invalid();
		}
		String until = parts.remove("UNTIL");
		if (until != null) {
			if (parts.containsKey("COUNT")) {
				throw invalid();
			}
			if (until.length() == 8) {
				IcsValues.date(until);
			}
			else {
				IcsValues.dateTime(until);
			}
		}
		if (days && (parts.containsKey("BYHOUR") || parts.containsKey("BYMINUTE") || parts.containsKey("BYSECOND"))) {
			throw invalid();
		}
		StringBuilder forRecur = new StringBuilder("FREQ=").append(frequency);
		Integer count = null;
		boolean shortened = false;
		long interval = 1;
		long candidates = 1;
		for (Map.Entry<String, String> part : parts.entrySet()) {
			String list = part.getValue();
			String written = list;
			switch (part.getKey()) {
				case "COUNT" -> {
					long wanted = number(list, 1, Integer.MAX_VALUE);
					shortened = wanted > MAX_COUNT;
					count = (int) Math.min(wanted, MAX_COUNT);
					written = count.toString();
				}
				case "INTERVAL" -> interval = number(list, 1, Integer.MAX_VALUE);
				case "BYSECOND", "BYMINUTE" -> candidates *= numbers(list, 0, 59, false);
				case "BYHOUR" -> candidates *= numbers(list, 0, 23, false);
				case "BYMONTHDAY" -> candidates *= numbers(list, 1, 31, true);
				case "BYYEARDAY" -> candidates *= numbers(list, 1, 366, true);
				case "BYWEEKNO" -> candidates *= numbers(list, 1, 53, true);
				case "BYMONTH" -> candidates *= numbers(list, 1, 12, false);
				case "BYSETPOS" -> numbers(list, 1, 366, true);
				case "BYDAY" -> candidates *= weekdays(list, frequency);
				case "WKST" -> {
					if (!WEEK_DAYS.contains(list)) {
						throw invalid();
					}
				}
				default -> throw invalid();
			}
			forRecur.append(';').append(part.getKey()).append('=').append(written);
		}
		boolean expandable = !BELOW_A_DAY.contains(frequency) && interval <= MAX_INTERVAL
				&& candidates <= MAX_CANDIDATES_PER_PERIOD;
		return new IcsRule(frequency, count, until, forRecur.toString(), expandable, shortened);
	}

	private static long number(String text, long min, long max) {
		if (!NUMBER.matcher(text).matches()) {
			throw invalid();
		}
		long value = Long.parseLong(text);
		if (value < min || value > max) {
			throw invalid();
		}
		return value;
	}

	/** The size of a number list, each value checked; [negative] allows counting from the end. */
	private static int numbers(String list, int min, int max, boolean negative) {
		String[] values = list.split(",", -1);
		if (values.length > MAX_LIST) {
			throw invalid();
		}
		for (String value : values) {
			long n = number(value, -max, max);
			if (!(n >= min || negative && -n >= Math.max(min, 1))) {
				throw invalid();
			}
		}
		return values.length;
	}

	private static int weekdays(String list, String frequency) {
		String[] values = list.split(",", -1);
		if (values.length > MAX_LIST) {
			throw invalid();
		}
		for (String value : values) {
			Matcher weekday = WEEKDAY.matcher(value);
			if (!weekday.matches()) {
				throw invalid();
			}
			if (weekday.group(1) != null) {
				long ordinal = Long.parseLong(weekday.group(1));
				// "The second Monday" exists within a month or a year, not within a week or a day.
				if (ordinal == 0 || Math.abs(ordinal) > 53 || !(frequency.equals("MONTHLY") || frequency.equals("YEARLY"))) {
					throw invalid();
				}
			}
		}
		return values.length;
	}

	private static DateTimeException invalid() {
		return new DateTimeException("not a readable RRULE");
	}
}
