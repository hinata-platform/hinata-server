package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.ics.IcsParser;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The arithmetic and the shape rules of a series (HIN-161), as static functions without Spring.
 *
 * <p>Every time here comes from a date, a wall-clock time and the series' zone, resolved by
 * {@link IcsParser#instant}: never from an instant plus a difference. A weekly 18:00 is 18:00 on
 * both sides of the change to summer time, and a shift from 22:00 to 06:00 over the night the
 * clocks go back lasts nine hours on that night.
 */
public final class ShiftSeriesPlan {

	/** How far ahead occurrences exist, in days from today in the series' zone. */
	public static final int HORIZON_DAYS = 90;

	/** Most occurrences one run materialises; a daily series reaches 90 days over two nights. */
	public static final int HORIZON_OCCURRENCES = 52;

	public static final int TEMPLATES_MAX = 50;

	/** Most shifts one horizon may hold for one series. */
	public static final int SHIFTS_IN_HORIZON_MAX = 2000;

	public static final int EXDATES_MAX = 366;

	/** Longest occurrence, on the wall clock. */
	public static final int DURATION_MAX_MINUTES = (int) ShiftRules.EVENT_MAX.toMinutes();

	private static final DateTimeFormatter RULE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private ShiftSeriesPlan() {
	}

	/** One occurrence: its day and the instants its event starts and ends. */
	public record Occurrence(LocalDate date, Instant startsAt, Instant endsAt) {
	}

	/** The times of one shift of an occurrence. */
	public record ShiftTimes(Instant startsAt, Instant endsAt) {
	}

	/** The occurrence of [series] on [date]. */
	public static Occurrence occurrence(ShiftSeries series, LocalDate date) {
		ZoneId zone = ShiftRules.zoneOf(series.getTimezone());
		LocalDateTime start = date.atTime(series.getFirstStart().toLocalTime());
		Instant startsAt = IcsParser.instant(start, zone);
		Instant endsAt = IcsParser.instant(start.plusMinutes(series.duration()), zone);
		// Fourteen days on the wall can be an hour more over the change back from summer time.
		if (Duration.between(startsAt, endsAt).compareTo(ShiftRules.EVENT_MAX) > 0) {
			endsAt = startsAt.plus(ShiftRules.EVENT_MAX);
		}
		return new Occurrence(date, ShiftRules.stored(startsAt), ShiftRules.stored(endsAt));
	}

	/** The times of [template] in the occurrence on [date]. */
	public static ShiftTimes shiftTimes(ShiftSeries series, ShiftTemplate template, LocalDate date) {
		ZoneId zone = ShiftRules.zoneOf(series.getTimezone());
		LocalDate day = date.plusDays(template.offset());
		LocalDateTime start = day.atTime(template.getStart());
		LocalDateTime end = (template.getEnd().isAfter(template.getStart()) ? day : day.plusDays(1))
				.atTime(template.getEnd());
		Instant startsAt = IcsParser.instant(start, zone);
		Instant endsAt = IcsParser.instant(end, zone);
		// A 24-hour shift over the night the clocks go back would last 25: it ends after 24.
		if (Duration.between(startsAt, endsAt).compareTo(ShiftRules.SHIFT_MAX) > 0) {
			endsAt = startsAt.plus(ShiftRules.SHIFT_MAX);
		}
		return new ShiftTimes(ShiftRules.stored(startsAt), ShiftRules.stored(endsAt));
	}

	/** The days of [series] from [from] to [to], both inclusive, its exdates left out. */
	public static List<LocalDate> dates(ShiftSeries series, LocalDate from, LocalDate to) {
		return dates(series.getRrule(), series.getFirstStart(), series.getTimezone(), series.getExdates(), from, to);
	}

	public static List<LocalDate> dates(String rrule, LocalDateTime firstStart, String timezone,
			List<LocalDate> exdates, LocalDate from, LocalDate to) {
		Set<LocalDate> skipped = exdates == null ? Set.of() : new HashSet<>(exdates);
		List<LocalDate> out = new ArrayList<>();
		for (ZonedDateTime start : expand(rrule, firstStart, ShiftRules.zoneOf(timezone), from, to)) {
			LocalDate day = start.toLocalDate();
			if (!skipped.contains(day) && (out.isEmpty() || !out.get(out.size() - 1).equals(day))) {
				out.add(day);
			}
		}
		return out;
	}

	/** {@link IcsParser#expand}, its refusals as the module's 400s. */
	static List<ZonedDateTime> expand(String rrule, LocalDateTime firstStart, ZoneId zone, LocalDate from,
			LocalDate to) {
		if (rrule == null || rrule.isBlank()) {
			throw ApiException.badRequest("error.shifts.rruleUnsupported");
		}
		try {
			return IcsParser.expand(rrule, firstStart, zone, from, to);
		}
		catch (IcsParser.RecurrenceRefused refused) {
			throw ApiException.badRequest(refused.reason() == IcsParser.RecurrenceRefused.Reason.TOO_COMPLEX
					? "error.shifts.rruleTooComplex" : "error.shifts.rruleUnsupported");
		}
	}

	/** The next horizon: up to [today] + 90 days or the 52nd day, whichever comes first. */
	public record Horizon(List<LocalDate> dates, LocalDate until) {
	}

	/**
	 * The days a run materialises for [series]: from the day after {@code materializedUntil}, but
	 * never before [today] nor before the first occurrence, to the end of the horizon.
	 */
	public static Horizon horizon(ShiftSeries series, LocalDate today) {
		LocalDate from = later(today, series.getFirstStart().toLocalDate());
		if (series.getMaterializedUntil() != null) {
			from = later(from, series.getMaterializedUntil().plusDays(1));
		}
		LocalDate limit = today.plusDays(HORIZON_DAYS);
		if (from.isAfter(limit)) {
			return new Horizon(List.of(), series.getMaterializedUntil());
		}
		List<LocalDate> dates = dates(series, from, limit);
		if (dates.size() >= HORIZON_OCCURRENCES) {
			dates = dates.subList(0, HORIZON_OCCURRENCES);
			return new Horizon(dates, dates.get(dates.size() - 1));
		}
		return new Horizon(dates, limit);
	}

	/** Whether no occurrence of [series] can come after [until]: UNTIL or COUNT is used up. */
	public static boolean endsBy(ShiftSeries series, LocalDate until) {
		Map<String, String> parts = parts(series.getRrule());
		if (parts.containsKey("UNTIL")) {
			return !LocalDate.parse(parts.get("UNTIL"), RULE_DATE).isAfter(until);
		}
		if (parts.containsKey("COUNT")) {
			int count = Integer.parseInt(parts.get("COUNT"));
			return expand(series.getRrule(), series.getFirstStart(), ShiftRules.zoneOf(series.getTimezone()),
					series.getFirstStart().toLocalDate(), until).size() >= count;
		}
		return false;
	}

	/** How many occurrences of [series] lie before [day], exdates included (COUNT counts them). */
	public static int countBefore(ShiftSeries series, LocalDate day) {
		if (!day.isAfter(series.getFirstStart().toLocalDate())) {
			return 0;
		}
		return expand(series.getRrule(), series.getFirstStart(), ShiftRules.zoneOf(series.getTimezone()),
				series.getFirstStart().toLocalDate(), day.minusDays(1)).size();
	}

	/** [rrule] closed on [lastDay]: COUNT gone, UNTIL set. */
	public static String closedOn(String rrule, LocalDate lastDay) {
		Map<String, String> parts = parts(rrule);
		parts.remove("COUNT");
		parts.put("UNTIL", RULE_DATE.format(lastDay));
		return written(parts);
	}

	/** [rrule] with COUNT set to [count], or as it is without COUNT. */
	public static String withCount(String rrule, int count) {
		Map<String, String> parts = parts(rrule);
		if (!parts.containsKey("COUNT")) {
			return rrule;
		}
		parts.put("COUNT", String.valueOf(Math.max(1, count)));
		return written(parts);
	}

	/** The parts of a rule the subset knows, upper case; anything unreadable is left out. */
	public static Map<String, String> parts(String rrule) {
		Map<String, String> parts = new LinkedHashMap<>();
		if (rrule == null) {
			return parts;
		}
		for (String part : rrule.strip().split(";")) {
			int equals = part.indexOf('=');
			if (equals > 0) {
				parts.put(part.substring(0, equals).strip().toUpperCase(Locale.ROOT),
						part.substring(equals + 1).strip().toUpperCase(Locale.ROOT));
			}
		}
		return parts;
	}

	private static String written(Map<String, String> parts) {
		StringBuilder out = new StringBuilder();
		parts.forEach((name, value) -> out.append(out.isEmpty() ? "" : ";").append(name).append('=').append(value));
		return out.toString();
	}

	// --- validation -------------------------------------------------------------------

	/**
	 * The fields of a series, checked the way an event's are, the rule run once through the engine
	 * (so an unsupported or too costly rule fails here), and every template through the shift rules
	 * (HIN-43 rules 1 to 7) against the first occurrence on the wall clock. Templates without a key
	 * get one; a repeated key is refused.
	 */
	public static void validate(ShiftSeries series, LocalDate today) {
		series.setTitle(ShiftRules.title(series.getTitle(), true));
		series.setDescription(ShiftRules.description(series.getDescription()));
		series.setLocation(ShiftRules.location(series.getLocation()));
		LocalDateTime first = series.getFirstStart();
		if (first == null || series.getDurationMinutes() == null) {
			throw ApiException.badRequest("error.shifts.timesRequired");
		}
		series.setFirstStart(first.withSecond(0).withNano(0));
		if (first.toLocalDate().isBefore(today.minusYears(1)) || first.toLocalDate().isAfter(today.plusYears(2))) {
			throw ApiException.badRequest("error.shifts.seriesStartOutOfRange");
		}
		if (series.getDurationMinutes() < 1) {
			throw ApiException.badRequest("error.shifts.endBeforeStart");
		}
		if (series.getDurationMinutes() > DURATION_MAX_MINUTES) {
			throw ApiException.badRequest("error.shifts.eventTooLong");
		}
		expand(series.getRrule(), series.getFirstStart(), ShiftRules.zoneOf(series.getTimezone()),
				first.toLocalDate(), first.toLocalDate());
		List<ShiftTemplate> templates = series.getShifts() == null ? new ArrayList<>() : series.getShifts();
		if (templates.size() > TEMPLATES_MAX) {
			throw ApiException.badRequest("error.shifts.seriesTooManyShifts", TEMPLATES_MAX);
		}
		// The wall clock of the first occurrence, read as UTC: the validators see wall-clock spans.
		Instant eventStart = first.toInstant(ZoneOffset.UTC);
		ShiftEvent wall = ShiftEvent.builder().startsAt(eventStart)
				.endsAt(eventStart.plus(Duration.ofMinutes(series.getDurationMinutes()))).build();
		Set<String> keys = new HashSet<>();
		for (ShiftTemplate template : templates) {
			if (template == null || template.getStart() == null || template.getEnd() == null) {
				throw ApiException.badRequest("error.shifts.timesRequired");
			}
			if (template.offset() < 0) {
				throw ApiException.badRequest("error.shifts.shiftOutsideEvent");
			}
			if (template.getKey() == null || template.getKey().isBlank()) {
				template.setKey(newKey());
			}
			if (template.getKey().length() > 64 || !keys.add(template.getKey())) {
				throw ApiException.badRequest("error.shifts.seriesTemplateKeyInvalid");
			}
			template.setTitle(ShiftRules.title(template.getTitle(), false));
			template.setStart(template.getStart().withSecond(0).withNano(0));
			template.setEnd(template.getEnd().withSecond(0).withNano(0));
			template.setRequiredHelpers(template.required());
			ShiftRules.helpers(template.required(), template.getMaxHelpers());
			LocalDate day = first.toLocalDate().plusDays(template.offset());
			LocalDateTime start = day.atTime(template.getStart());
			LocalDateTime end = (template.getEnd().isAfter(template.getStart()) ? day : day.plusDays(1))
					.atTime(template.getEnd());
			ShiftRules.shiftSpan(start.toInstant(ZoneOffset.UTC), end.toInstant(ZoneOffset.UTC), wall);
		}
		series.setShifts(templates);
		if (series.getExdates() == null) {
			series.setExdates(new ArrayList<>());
		}
	}

	/** Refuses a horizon that would hold more than {@link #SHIFTS_IN_HORIZON_MAX} shifts. */
	public static void assertHorizonFits(ShiftSeries series, int occurrences) {
		int templates = series.getShifts() == null ? 0 : series.getShifts().size();
		if ((long) occurrences * templates > SHIFTS_IN_HORIZON_MAX) {
			throw ApiException.badRequest("error.shifts.seriesTooLarge", SHIFTS_IN_HORIZON_MAX);
		}
	}

	/** A template from a shift of an occurrence that starts at [occurrenceStart] (wall clock). */
	public static ShiftTemplate templateOf(Shift shift, LocalDateTime occurrenceStart, ZoneId zone, String key) {
		LocalDateTime start = LocalDateTime.ofInstant(shift.getStartsAt(), zone);
		LocalDateTime end = LocalDateTime.ofInstant(shift.getEndsAt(), zone);
		return ShiftTemplate.builder()
				.key(key)
				.title(shift.getTitle())
				.dayOffset((int) java.time.temporal.ChronoUnit.DAYS.between(occurrenceStart.toLocalDate(),
						start.toLocalDate()))
				.start(start.toLocalTime())
				.end(end.toLocalTime())
				.requiredHelpers(shift.required())
				.maxHelpers(shift.getMaxHelpers())
				.build();
	}

	public static String newKey() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	static LocalDate later(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	/** Today in the series' zone. */
	public static LocalDate today(Instant now, String timezone) {
		return LocalDate.ofInstant(now, ShiftRules.zoneOf(timezone));
	}
}
