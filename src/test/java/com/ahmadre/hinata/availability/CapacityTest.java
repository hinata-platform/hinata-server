package com.ahmadre.hinata.availability;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capacity for sample weeks, with fixed dates: the week of 21 December 2026, where Christmas Eve
 * falls on a Thursday and Christmas Day on a Friday.
 */
class CapacityTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 12, 21);
	private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
	private static final LocalDate THURSDAY = MONDAY.plusDays(3);
	private static final LocalDate FRIDAY = MONDAY.plusDays(4);
	private static final LocalDate SUNDAY = MONDAY.plusDays(6);

	/** Eight hours Monday to Friday, nothing at the weekend. */
	private static final ToIntFunction<LocalDate> FULL_TIME =
			day -> day.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue() ? 480 : 0;

	@Test
	void aWeekWithAHolidayAndHalfAVacationDay() {
		Capacity.Result week = Capacity.of(MONDAY, SUNDAY, FULL_TIME, Map.of(FRIDAY, false),
				List.of(new Capacity.Absence(WEDNESDAY, WEDNESDAY, true)));

		assertThat(week.scheduledMinutes()).isEqualTo(5 * 480);
		assertThat(week.holidayMinutes()).isEqualTo(480);
		assertThat(week.absenceMinutes()).isEqualTo(240);
		assertThat(week.capacityMinutes()).isEqualTo(3 * 480 + 240);
		assertThat(week.days()).hasSize(7);
		assertThat(week.days().get(2).capacityMinutes()).isEqualTo(240);
		assertThat(week.days().get(4).capacityMinutes()).isZero();
	}

	@Test
	void halfAHolidayAndHalfAVacationDayLeaveNothing() {
		Capacity.Result eve = Capacity.of(THURSDAY, THURSDAY, FULL_TIME, Map.of(THURSDAY, true),
				List.of(new Capacity.Absence(THURSDAY, THURSDAY, true)));

		assertThat(eve.holidayMinutes()).isEqualTo(240);
		assertThat(eve.absenceMinutes()).isEqualTo(240);
		assertThat(eve.capacityMinutes()).isZero();
	}

	@Test
	void anOddDaySplitsIntoTwoHalvesThatMakeTheDay() {
		Capacity.Result eve = Capacity.of(THURSDAY, THURSDAY, day -> 45, Map.of(THURSDAY, true),
				List.of(new Capacity.Absence(THURSDAY, THURSDAY, true)));

		assertThat(eve.holidayMinutes() + eve.absenceMinutes()).isEqualTo(45);
		assertThat(eve.capacityMinutes()).isZero();
	}

	@Test
	void aVacationOverAHolidayDoesNotCountTheHolidayTwice() {
		Capacity.Result week = Capacity.of(MONDAY, SUNDAY, FULL_TIME, Map.of(FRIDAY, false),
				List.of(new Capacity.Absence(MONDAY, SUNDAY, false)));

		assertThat(week.holidayMinutes()).isEqualTo(480);
		assertThat(week.absenceMinutes()).isEqualTo(4 * 480);
		assertThat(week.capacityMinutes()).isZero();
	}

	@Test
	void aDayWithoutPlannedHoursHasNothingToTake() {
		Capacity.Result weekend = Capacity.of(FRIDAY.plusDays(1), SUNDAY, FULL_TIME, Map.of(SUNDAY, false),
				List.of(new Capacity.Absence(FRIDAY.plusDays(1), SUNDAY, false)));

		assertThat(weekend.scheduledMinutes()).isZero();
		assertThat(weekend.holidayMinutes()).isZero();
		assertThat(weekend.absenceMinutes()).isZero();
		assertThat(weekend.capacityMinutes()).isZero();
	}

	@Test
	void aHalfDayOverSeveralDaysCountsAsWholeDays() {
		// The service refuses such an absence; a document written before that rule still counts
		// in whole days rather than in halves nobody chose.
		Capacity.Result days = Capacity.of(MONDAY, WEDNESDAY, FULL_TIME, Map.of(),
				List.of(new Capacity.Absence(MONDAY, WEDNESDAY, true)));

		assertThat(days.absenceMinutes()).isEqualTo(3 * 480);
	}

	@Test
	void twoAbsencesOnOneDayNeverTakeMoreThanTheDay() {
		Capacity.Result day = Capacity.of(MONDAY, MONDAY, FULL_TIME, Map.of(),
				List.of(new Capacity.Absence(MONDAY, MONDAY, false), new Capacity.Absence(MONDAY, MONDAY, true)));

		assertThat(day.absenceMinutes()).isEqualTo(480);
		assertThat(day.capacityMinutes()).isZero();
	}
}
