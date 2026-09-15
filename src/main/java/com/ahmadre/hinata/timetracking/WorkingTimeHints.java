package com.ahmadre.hinata.timetracking;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a person's own entries say about the rules of the Working Hours Act, and
 * whether an entry was recorded late — as hints to that person, nothing more.
 *
 * <p>Pure and Spring-free, like {@link ApprovalPeriods}: the arithmetic has one
 * home, a test beside it, and no second copy in the app. It reads nothing and
 * stores nothing. That is the legal design, not an economy: a hint that was kept,
 * counted or shown to anybody else would be an evaluation of somebody's working
 * behaviour (§ 87 Abs. 1 Nr. 6 BetrVG, R7), which is exactly what these are not.
 *
 * <ul>
 * <li><b>Daily maximum</b> — more than ten hours booked on one day (§ 3 ArbZG).</li>
 * <li><b>Short rest</b> — less than eleven hours between the end of one working
 * day and the start of the next (§ 5 ArbZG). Only timed entries can say when work
 * began and ended, so a day typed as a bare duration answers nothing here.</li>
 * <li><b>Sunday work</b> — hours booked on a Sunday (§ 9 ArbZG).</li>
 * <li><b>Holiday work</b> — hours booked on a public holiday of the calendar the
 * person follows (§ 9 ArbZG, stage 10). A hint, like the Sunday one: § 16 Abs. 2
 * ArbZG wants that work recorded, so the entry stands.</li>
 * <li><b>Late entry</b> — recorded more than N days after the day it describes
 * (R9). Measured from {@code createdAt}, so a later edit does not make an entry
 * late, and an entry without one (written before the field existed) never is.</li>
 * </ul>
 */
final class WorkingTimeHints {

	/** § 3 ArbZG: the working day may reach ten hours, not more. */
	static final int DAILY_MAXIMUM_MINUTES = 10 * 60;

	/** § 5 Abs. 1 ArbZG: eleven uninterrupted hours between two working days. */
	static final Duration MINIMUM_REST = Duration.ofHours(11);

	private WorkingTimeHints() {
	}

	/** Wire names. {@code HOLIDAY_WORK} came last so that the order of the others stays. */
	enum Kind {
		DAILY_MAXIMUM, SHORT_REST, SUNDAY_WORK, LATE_ENTRY, HOLIDAY_WORK
	}

	/** The part of an entry the rules look at. */
	record Entry(String id, LocalDate date, int minutes, Instant startedAt, Instant endedAt,
			Instant createdAt) {
	}

	/** Which of the hints are switched on. {@code lateEntryHintDays} null ⇒ none. */
	record Rules(boolean workingTimeAct, Integer lateEntryHintDays) {
	}

	/**
	 * One hint. {@code minutes} for a day's total, {@code restMinutes} for the rest
	 * that was too short, {@code entryId} and {@code daysLate} for a late entry.
	 */
	record Hint(Kind kind, LocalDate date, String entryId, Integer minutes, Integer restMinutes,
			Integer daysLate) {
	}

	/** The hints without a holiday calendar. */
	static List<Hint> of(List<Entry> entries, LocalDate from, LocalDate to, ZoneId zone,
			Rules rules) {
		return of(entries, from, to, zone, rules, Set.of());
	}

	/**
	 * The hints for the days {@code from} to {@code to}, both included.
	 *
	 * <p>{@code entries} may reach one day before {@code from}: whether the rest
	 * before the first day of the window was long enough depends on when the day
	 * before it ended, and a window that cannot see that day would answer "fine" for
	 * a reason that has nothing to do with the entries.
	 *
	 * @param zone     the person's zone, which turns {@code createdAt} into the day it was
	 *                 recorded on
	 * @param holidays the public holidays in the window
	 */
	static List<Hint> of(List<Entry> entries, LocalDate from, LocalDate to, ZoneId zone,
			Rules rules, Set<LocalDate> holidays) {
		Map<LocalDate, List<Entry>> byDay = new TreeMap<>();
		for (Entry entry : entries) {
			if (entry.date() != null) {
				byDay.computeIfAbsent(entry.date(), day -> new ArrayList<>()).add(entry);
			}
		}
		List<Hint> hints = new ArrayList<>();
		if (rules.workingTimeAct()) {
			workingTimeAct(byDay, from, to, holidays, hints);
		}
		if (rules.lateEntryHintDays() != null) {
			lateEntries(byDay, from, to, zone, rules.lateEntryHintDays(), hints);
		}
		hints.sort(Comparator.comparing(Hint::date).thenComparing(Hint::kind));
		return hints;
	}

	private static void workingTimeAct(Map<LocalDate, List<Entry>> byDay, LocalDate from,
			LocalDate to, Set<LocalDate> holidays, List<Hint> hints) {
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			List<Entry> today = byDay.getOrDefault(day, List.of());
			int total = today.stream().mapToInt(Entry::minutes).sum();
			if (total > DAILY_MAXIMUM_MINUTES) {
				hints.add(new Hint(Kind.DAILY_MAXIMUM, day, null, total, null, null));
			}
			if (total > 0 && day.getDayOfWeek() == DayOfWeek.SUNDAY) {
				hints.add(new Hint(Kind.SUNDAY_WORK, day, null, total, null, null));
			}
			if (total > 0 && holidays.contains(day)) {
				hints.add(new Hint(Kind.HOLIDAY_WORK, day, null, total, null, null));
			}
			Duration rest = restBefore(byDay.getOrDefault(day.minusDays(1), List.of()), today);
			if (rest != null && rest.compareTo(MINIMUM_REST) < 0) {
				hints.add(new Hint(Kind.SHORT_REST, day, null, null, (int) rest.toMinutes(), null));
			}
		}
	}

	/**
	 * The time between the last end of one day and the first start of the next, or
	 * null when either is unknown or the two overlap. An overlap is a different
	 * statement — two entries claiming the same hour — and the editor already warns
	 * about it; calling it a short rest would be wrong twice.
	 */
	private static Duration restBefore(List<Entry> dayBefore, List<Entry> day) {
		Instant lastEnd = dayBefore.stream().map(Entry::endedAt).filter(java.util.Objects::nonNull)
				.max(Comparator.naturalOrder()).orElse(null);
		Instant firstStart = day.stream().map(Entry::startedAt).filter(java.util.Objects::nonNull)
				.min(Comparator.naturalOrder()).orElse(null);
		if (lastEnd == null || firstStart == null || !firstStart.isAfter(lastEnd)) {
			return null;
		}
		return Duration.between(lastEnd, firstStart);
	}

	private static void lateEntries(Map<LocalDate, List<Entry>> byDay, LocalDate from, LocalDate to,
			ZoneId zone, int afterDays, List<Hint> hints) {
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			for (Entry entry : byDay.getOrDefault(day, List.of())) {
				if (entry.createdAt() == null) {
					continue;
				}
				long late = ChronoUnit.DAYS.between(day, LocalDate.ofInstant(entry.createdAt(), zone));
				if (late > afterDays) {
					hints.add(new Hint(Kind.LATE_ENTRY, day, entry.id(), null, null, (int) late));
				}
			}
		}
	}
}
