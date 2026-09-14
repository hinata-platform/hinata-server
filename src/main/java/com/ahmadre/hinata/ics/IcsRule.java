package com.ahmadre.hinata.ics;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An RRULE checked part by part before ical4j's recurrence engine sees it.
 *
 * <p>The engine is trusted with the arithmetic of RFC 5545, not with input. Measured
 * against ical4j 4.3.0: a series without COUNT jumps to the window by doubling an int,
 * which overflows and then never returns once the series starts more than 2³⁰ steps
 * before the window; a series with COUNT is walked from its first occurrence; every BY
 * list multiplies the candidates the engine builds for each period, also for periods
 * that turn out empty; a period that stays empty is followed, within the same step, by
 * up to a thousand more before the engine gives up; and BYDAY is expanded from every
 * date the other lists built, across its whole month or year, and checked with a
 * parallel stream. So:
 *
 * <ul>
 * <li>only DAILY, WEEKLY, MONTHLY and YEARLY are expanded, where no year between 1 and
 * 9999 is 2³⁰ steps away;</li>
 * <li>COUNT is cut at {@link #MAX_COUNT};</li>
 * <li>a rule is read but not expanded when its INTERVAL is above {@link #MAX_INTERVAL},
 * when it names a list RFC 5545 has no use for with its frequency, such as BYMONTHDAY in
 * a weekly rule, when a period may hold more than {@link #MAX_CANDIDATES_PER_PERIOD}
 * candidates the way the engine builds them, when it names a weekday position no month
 * has, such as a sixth Monday, or when its BYSETPOS lies beyond the candidates every
 * period is sure to hold, which could leave every period empty;</li>
 * <li>repeated values in a list are dropped, so Monday written three hundred times costs
 * what one Monday costs;</li>
 * <li>UNTIL is left out of what the engine gets, because it would compare a UTC value
 * with wall-clock candidates. The parser applies it itself.</li>
 * </ul>
 *
 * <p>A rule that breaks RFC 5545 is refused with a {@link DateTimeException}. A valid
 * rule the engine is not given is still read, and the calendar says it was cut.
 *
 * @param forRecur   the rule as the engine gets it: the checked parts, lists without
 *                   repeats, COUNT cut, no UNTIL
 * @param until      the rule's UNTIL, or null
 * @param expandable whether the engine may expand the rule
 * @param shortened  COUNT was larger than {@link #MAX_COUNT}
 */
record IcsRule(String forRecur, Until until, boolean expandable, boolean shortened) {

	static final int MAX_COUNT = 5_000;
	static final int MAX_INTERVAL = 100;

	/** The rules calendars write stay far below: a last weekday of the month has 25. */
	static final int MAX_CANDIDATES_PER_PERIOD = 100;

	private static final int MAX_LENGTH = 1_024;
	private static final int MAX_LIST = 366;
	private static final Set<String> FREQUENCIES =
			Set.of("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY");
	private static final Set<String> EXPANDED = Set.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY");

	/** The lists the table in RFC 5545 3.3.10 marks N/A for an expanded frequency. */
	private static final Map<String, Set<String>> NOT_APPLICABLE = Map.of(
			"DAILY", Set.of("BYWEEKNO", "BYYEARDAY"),
			"WEEKLY", Set.of("BYWEEKNO", "BYYEARDAY", "BYMONTHDAY"),
			"MONTHLY", Set.of("BYWEEKNO", "BYYEARDAY"));

	private static final Set<String> WEEK_DAYS = Set.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");
	private static final List<String> TIMES_OF_DAY = List.of("BYHOUR", "BYMINUTE", "BYSECOND");
	private static final Pattern WEEKDAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
	private static final Pattern NUMBER = Pattern.compile("[+-]?\\d{1,10}");

	/** The values each number list may hold; a negative value counts from the end where allowed. */
	private static final Map<String, Range> RANGES = Map.of(
			"BYSECOND", new Range(0, 59, false),
			"BYMINUTE", new Range(0, 59, false),
			"BYHOUR", new Range(0, 23, false),
			"BYMONTHDAY", new Range(1, 31, true),
			"BYYEARDAY", new Range(1, 366, true),
			"BYWEEKNO", new Range(1, 53, true),
			"BYMONTH", new Range(1, 12, false),
			"BYSETPOS", new Range(1, 366, true));

	/** UNTIL as written: a date, or a time in UTC or on the wall of the series' own zone. */
	record Until(LocalDate date, LocalDateTime local, boolean utc) {

		/** The last instant the rule allows under [rules]; a date allows its whole day. */
		Instant instant(ZoneRules rules) {
			if (date != null) {
				return IcsZones.instant(date.plusDays(1).atStartOfDay(), rules).minusNanos(1);
			}
			return utc ? local.toInstant(ZoneOffset.UTC) : IcsZones.instant(local, rules);
		}

		/** The last day the rule allows. */
		LocalDate day() {
			return date != null ? date : local.toLocalDate();
		}
	}

	/** Checks [value]; [days] says the series is one of whole days, where times of day have no place. */
	static IcsRule read(String value, boolean days) {
		if (value.length() > MAX_LENGTH) {
			throw invalid();
		}
		Map<String, String> parts = parts(value);
		String frequency = parts.remove("FREQ");
		if (frequency == null || !FREQUENCIES.contains(frequency)) {
			throw invalid();
		}
		Until until = until(parts.remove("UNTIL"));
		if (until != null && parts.containsKey("COUNT")) {
			throw invalid();
		}
		if (days && TIMES_OF_DAY.stream().anyMatch(parts::containsKey)) {
			throw invalid();
		}
		StringBuilder forRecur = new StringBuilder("FREQ=").append(frequency);
		Map<String, Integer> sizes = new LinkedHashMap<>();
		Weekdays weekdays = new Weekdays("", Set.of(), List.of());
		boolean shortened = false;
		long interval = 1;
		long setPosition = 0;
		for (Map.Entry<String, String> part : parts.entrySet()) {
			String name = part.getKey();
			String list = part.getValue();
			String written;
			switch (name) {
				case "COUNT" -> {
					long count = number(list, 1, Integer.MAX_VALUE);
					shortened = count > MAX_COUNT;
					written = Long.toString(Math.min(count, MAX_COUNT));
				}
				case "INTERVAL" -> {
					interval = number(list, 1, Integer.MAX_VALUE);
					written = Long.toString(interval);
				}
				case "BYDAY" -> {
					weekdays = weekdays(list, frequency);
					written = weekdays.written();
				}
				case "WKST" -> {
					if (!WEEK_DAYS.contains(list)) {
						throw invalid();
					}
					written = list;
				}
				default -> {
					Range range = RANGES.get(name);
					if (range == null) {
						throw invalid();
					}
					Numbers numbers = numbers(list, range);
					sizes.put(name, numbers.size());
					if (name.equals("BYSETPOS")) {
						setPosition = numbers.largest();
					}
					written = numbers.written();
				}
			}
			forRecur.append(';').append(name).append('=').append(written);
		}
		boolean applicable = sizes.keySet().stream().noneMatch(NOT_APPLICABLE.getOrDefault(frequency, Set.of())::contains);
		boolean withinMonths = frequency.equals("MONTHLY") || frequency.equals("YEARLY") && sizes.containsKey("BYMONTH");
		boolean positionsExist = weekdays.largestOrdinal() <= (withinMonths ? 5 : 53);
		boolean setPositionFound = setPosition == 0 || setPosition <= leastPerPeriod(frequency, sizes, weekdays);
		boolean expandable = EXPANDED.contains(frequency) && interval <= MAX_INTERVAL && applicable
				&& candidates(frequency, sizes, weekdays) <= MAX_CANDIDATES_PER_PERIOD
				&& positionsExist && setPositionFound;
		return new IcsRule(forRecur.toString(), until, expandable, shortened);
	}

	/**
	 * An upper bound on the candidates the engine builds for one period. It follows ical4j
	 * where ical4j and the table in RFC 5545 3.3.10 differ: the engine expands BYDAY from
	 * every date the other lists built, across the whole month or year, also where the
	 * RFC has BYDAY only limit them.
	 */
	private static long candidates(String frequency, Map<String, Integer> sizes, Weekdays weekdays) {
		int months = sizes.getOrDefault("BYMONTH", 0);
		int weeks = sizes.getOrDefault("BYWEEKNO", 0);
		int yearDays = sizes.getOrDefault("BYYEARDAY", 0);
		int monthDays = sizes.getOrDefault("BYMONTHDAY", 0);
		long estimate = 1;
		if (frequency.equals("YEARLY")) {
			estimate *= Math.max(months, 1);
			estimate *= weeks == 0 ? 1 : weeks * 7L;
			estimate *= Math.max(yearDays, 1);
			estimate *= monthDays == 0 ? 1 : (long) monthDays * (months > 0 ? 1 : 12);
		}
		else if (frequency.equals("MONTHLY")) {
			estimate *= Math.max(monthDays, 1);
		}
		if (weekdays.count() > 0) {
			long perPlainDay = switch (frequency) {
				case "MONTHLY" -> 5;
				case "YEARLY" -> months > 0 ? 5 : 53;
				default -> 1;
			};
			estimate *= weekdays.plain() * perPlainDay + weekdays.ordinal();
		}
		for (String time : TIMES_OF_DAY) {
			estimate *= Math.max(sizes.getOrDefault(time, 0), 1);
		}
		return estimate;
	}

	/**
	 * The candidates every period of the rule is sure to hold, for the rules BYSETPOS is
	 * written with: weekdays within a week, a month or a year, and times of day; 0 for any
	 * other shape, where no position is risked. Every month has each weekday at least four
	 * times and every year at least 52 times. A BYSETPOS up to this number finds a
	 * candidate in every period; one beyond it may find none in any, and the engine builds
	 * a thousand empty periods in one step before it gives up.
	 */
	private static long leastPerPeriod(String frequency, Map<String, Integer> sizes, Weekdays weekdays) {
		boolean onlyWeekdays = weekdays.count() > 0 && !sizes.containsKey("BYWEEKNO")
				&& !sizes.containsKey("BYYEARDAY") && !sizes.containsKey("BYMONTHDAY");
		if (!onlyWeekdays) {
			return 0;
		}
		long perMonth = 4L * weekdays.plain() + weekdays.surelyNamed(4);
		long least = switch (frequency) {
			case "WEEKLY" -> weekdays.plain();
			case "MONTHLY" -> perMonth;
			case "YEARLY" -> sizes.containsKey("BYMONTH")
					? sizes.get("BYMONTH") * perMonth
					: 52L * weekdays.plain() + weekdays.surelyNamed(52);
			default -> 0;
		};
		for (String time : TIMES_OF_DAY) {
			least *= Math.max(sizes.getOrDefault(time, 0), 1);
		}
		return least;
	}

	private static Map<String, String> parts(String value) {
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
		return parts;
	}

	private static Until until(String written) {
		if (written == null) {
			return null;
		}
		if (written.length() == 8) {
			return new Until(IcsValues.date(written), null, false);
		}
		return new Until(null, IcsValues.dateTime(written), IcsValues.isUtc(written));
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

	private static Numbers numbers(String list, Range range) {
		String[] values = list.split(",", -1);
		if (values.length > MAX_LIST) {
			throw invalid();
		}
		Set<Long> distinct = new LinkedHashSet<>();
		long largest = 0;
		for (String value : values) {
			long n = number(value, -range.max(), range.max());
			if (!(n >= range.min() || range.negative() && -n >= Math.max(range.min(), 1))) {
				throw invalid();
			}
			if (distinct.add(n)) {
				largest = Math.max(largest, Math.abs(n));
			}
		}
		return new Numbers(distinct.stream().map(String::valueOf).collect(Collectors.joining(",")), distinct.size(),
				largest);
	}

	private static Weekdays weekdays(String list, String frequency) {
		String[] values = list.split(",", -1);
		if (values.length > MAX_LIST) {
			throw invalid();
		}
		Set<String> written = new LinkedHashSet<>();
		Set<String> plainDays = new LinkedHashSet<>();
		List<Ordinal> ordinals = new ArrayList<>();
		for (String value : values) {
			Matcher weekday = WEEKDAY.matcher(value);
			if (!weekday.matches()) {
				throw invalid();
			}
			String day = weekday.group(2);
			if (weekday.group(1) == null) {
				if (written.add(day)) {
					plainDays.add(day);
				}
				continue;
			}
			long position = Long.parseLong(weekday.group(1));
			// "The second Monday" exists within a month or a year, not within a week or a day.
			if (position == 0 || Math.abs(position) > 53 || !(frequency.equals("MONTHLY") || frequency.equals("YEARLY"))) {
				throw invalid();
			}
			if (written.add(position + day)) {
				ordinals.add(new Ordinal(position, day));
			}
		}
		return new Weekdays(String.join(",", written), Set.copyOf(plainDays), List.copyOf(ordinals));
	}

	private static DateTimeException invalid() {
		return new DateTimeException("not a readable RRULE");
	}

	private record Range(int min, int max, boolean negative) {
	}

	private record Numbers(String written, int size, long largest) {
	}

	/** A weekday named with a position, such as the second Monday (2MO) or the last Friday (-1FR). */
	private record Ordinal(long position, String day) {
	}

	private record Weekdays(String written, Set<String> plainDays, List<Ordinal> ordinals) {

		int plain() {
			return plainDays.size();
		}

		int ordinal() {
			return ordinals.size();
		}

		int count() {
			return plain() + ordinal();
		}

		long largestOrdinal() {
			return ordinals.stream().mapToLong(ordinal -> Math.abs(ordinal.position())).max().orElse(0);
		}

		/**
		 * The days the positions are sure to name in a period that has every weekday at
		 * least [occurrences] times. A position counted from the start and one counted from
		 * the end may name the same day, so for each weekday only the larger of the two
		 * groups counts, and a weekday that is also named plainly counts there already.
		 */
		long surelyNamed(long occurrences) {
			long days = 0;
			for (String day : WEEK_DAYS) {
				if (plainDays.contains(day)) {
					continue;
				}
				long fromStart = ordinals.stream()
						.filter(ordinal -> ordinal.day().equals(day) && ordinal.position() > 0 && ordinal.position() <= occurrences)
						.count();
				long fromEnd = ordinals.stream()
						.filter(ordinal -> ordinal.day().equals(day) && ordinal.position() < 0 && -ordinal.position() <= occurrences)
						.count();
				days += Math.max(fromStart, fromEnd);
			}
			return days;
		}
	}
}
