package com.ahmadre.hinata.availability;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Capacity over a window: the planned minutes of every day, less holidays and absences.
 *
 * <p>Pure and Spring-free, like {@code timetracking.ApprovalPeriods}: one home for the arithmetic,
 * a test beside it, and no copy in the app.
 *
 * <p>A day is counted in halves. A holiday takes the whole day or its first half, an absence the
 * whole day or a half, and together they never take more than the day: half a holiday and half a
 * vacation day leave nothing, and a vacation over a holiday does not count the holiday twice. An
 * odd number of minutes splits into {@code minutes / 2} and the rest, so two halves are the day.
 */
public final class Capacity {

	private Capacity() {
	}

	/** An absence as capacity sees it: a span of days, a half day only on a single day. */
	public record Absence(LocalDate from, LocalDate to, boolean halfDay) {
	}

	/** One day. {@code capacityMinutes} is what is left of {@code scheduledMinutes}. */
	public record Day(LocalDate date, int scheduledMinutes, int holidayMinutes, int absenceMinutes,
			int capacityMinutes) {
	}

	public record Result(int scheduledMinutes, int holidayMinutes, int absenceMinutes,
			int capacityMinutes, List<Day> days) {
	}

	/**
	 * @param scheduled the planned minutes of a day
	 * @param holidays  the holidays in the window, each true for a half day
	 * @param absences  the absences touching the window
	 */
	public static Result of(LocalDate from, LocalDate to, ToIntFunction<LocalDate> scheduled,
			Map<LocalDate, Boolean> holidays, List<Absence> absences) {
		List<Day> days = new ArrayList<>();
		int scheduledSum = 0;
		int holidaySum = 0;
		int absenceSum = 0;
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			int minutes = Math.max(0, scheduled.applyAsInt(day));
			int holidayHalves = holidayHalves(holidays.get(day));
			int absenceHalves = Math.min(2 - holidayHalves, absenceHalves(day, absences));
			int holiday = halves(minutes, 0, holidayHalves);
			int absence = halves(minutes, holidayHalves, absenceHalves);
			int capacity = minutes - holiday - absence;
			days.add(new Day(day, minutes, holiday, absence, capacity));
			scheduledSum += minutes;
			holidaySum += holiday;
			absenceSum += absence;
		}
		return new Result(scheduledSum, holidaySum, absenceSum,
				scheduledSum - holidaySum - absenceSum, List.copyOf(days));
	}

	private static int holidayHalves(Boolean halfDay) {
		if (halfDay == null) {
			return 0;
		}
		return halfDay ? 1 : 2;
	}

	private static int absenceHalves(LocalDate day, List<Absence> absences) {
		int halves = 0;
		for (Absence absence : absences) {
			if (!day.isBefore(absence.from()) && !day.isAfter(absence.to())) {
				halves += absence.halfDay() && absence.from().equals(absence.to()) ? 1 : 2;
			}
		}
		return Math.min(2, halves);
	}

	/** The minutes of [count] halves of a day, starting after [taken] halves already counted. */
	private static int halves(int minutes, int taken, int count) {
		int first = minutes / 2;
		int second = minutes - first;
		int total = 0;
		for (int half = taken; half < taken + count; half++) {
			total += half == 0 ? first : second;
		}
		return total;
	}
}
