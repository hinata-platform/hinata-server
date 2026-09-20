package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.RelativeDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one calculation the whole feature rests on, tested where it is cheapest to test: no Spring,
 * no database, no holiday service.
 *
 * <p>The cases that matter are the ones a second implementation would get wrong — a week across a
 * month boundary, a leap day, counting backwards over Christmas with a calendar, and the zero that
 * has to mean "the day of the event itself" in both bases.
 */
class RelativeDatesTest {

	private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 12);

	private static RelativeDate days(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.DAYS, RelativeDate.Basis.CALENDAR);
	}

	private static RelativeDate weeks(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.WEEKS, RelativeDate.Basis.CALENDAR);
	}

	private static RelativeDate workdays(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.DAYS, RelativeDate.Basis.WORKING);
	}

	@Nested
	@DisplayName("calendar days")
	class CalendarDays {

		@Test
		@DisplayName("before and after are the sign, not a second field")
		void signCarriesTheDirection() {
			assertThat(RelativeDates.resolve(THURSDAY, days(-28), null))
					.isEqualTo(LocalDate.of(2026, 10, 15));
			assertThat(RelativeDates.resolve(THURSDAY, days(3), null))
					.isEqualTo(LocalDate.of(2026, 11, 15));
		}

		@Test
		@DisplayName("zero is the day of the event")
		void zeroLandsOnTheAnchor() {
			assertThat(RelativeDates.resolve(THURSDAY, days(0), null)).isEqualTo(THURSDAY);
		}

		@Test
		@DisplayName("a week is seven days, across a month and across a year")
		void weeksAreSevenDays() {
			assertThat(RelativeDates.resolve(THURSDAY, weeks(-4), null))
					.isEqualTo(LocalDate.of(2026, 10, 15));
			assertThat(RelativeDates.resolve(LocalDate.of(2027, 1, 7), weeks(-2), null))
					.isEqualTo(LocalDate.of(2026, 12, 24));
		}

		@Test
		@DisplayName("a leap day is a day like any other")
		void leapYearsNeedNoSpecialCase() {
			assertThat(RelativeDates.resolve(LocalDate.of(2028, 3, 1), days(-1), null))
					.isEqualTo(LocalDate.of(2028, 2, 29));
			assertThat(RelativeDates.resolve(LocalDate.of(2028, 2, 29), weeks(1), null))
					.isEqualTo(LocalDate.of(2028, 3, 7));
		}
	}

	@Nested
	@DisplayName("working days")
	class WorkingDays {

		@Test
		@DisplayName("without a calendar only weekends are skipped")
		void weekendsAlone() {
			// Thursday 12 Nov 2026 minus four working days: Wed, Tue, Mon, then Friday the 6th.
			assertThat(RelativeDates.resolve(THURSDAY, workdays(-4), null))
					.isEqualTo(LocalDate.of(2026, 11, 6));
			// And forwards over a weekend: Fri, then Mon the 16th.
			assertThat(RelativeDates.resolve(THURSDAY, workdays(2), null))
					.isEqualTo(LocalDate.of(2026, 11, 16));
		}

		@Test
		@DisplayName("zero stays on the event, even when the event is a Sunday")
		void zeroDoesNotMoveToAWorkday() {
			LocalDate sunday = LocalDate.of(2026, 11, 15);
			assertThat(RelativeDates.resolve(sunday, workdays(0), null)).isEqualTo(sunday);
		}

		@Test
		@DisplayName("the day of the event is never counted as one of the days")
		void theAnchorIsNotCounted() {
			// A Monday event with "one working day before" is the Friday, not the Monday: the
			// day of the event is not a day of work to be had before it.
			LocalDate monday = LocalDate.of(2026, 11, 16);
			assertThat(RelativeDates.resolve(monday, workdays(-1), null))
					.isEqualTo(LocalDate.of(2026, 11, 13));
		}

		@Test
		@DisplayName("a holiday calendar is skipped as well, counting backwards over Christmas")
		void holidaysAreSkipped() {
			// 25 and 26 December 2026 are a Friday and a Saturday; 1 January 2027 is a Friday.
			Set<LocalDate> holidays = Set.of(
					LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26),
					LocalDate.of(2027, 1, 1));
			WorkdayCalendar calendar = date ->
					WorkdayCalendar.WEEKENDS_ONLY.isWorkday(date) && !holidays.contains(date);

			// From Monday 4 Jan 2027, three working days back: Thu 31st, Wed 30th, Tue 29th.
			// Friday the 1st and the weekend do not count, and neither does the 25th further back.
			assertThat(RelativeDates.resolve(LocalDate.of(2027, 1, 4), workdays(-3), calendar))
					.isEqualTo(LocalDate.of(2026, 12, 29));
		}

		@Test
		@DisplayName("the same offset gives a different day with and without a calendar")
		void theCalendarActuallyChangesTheAnswer() {
			// The regression this test exists for: a calendar that is looked up, loaded and then
			// not consulted would pass every other case in this file.
			LocalDate event = LocalDate.of(2027, 1, 4);
			WorkdayCalendar withNewYear = date ->
					WorkdayCalendar.WEEKENDS_ONLY.isWorkday(date)
							&& !date.equals(LocalDate.of(2027, 1, 1));

			assertThat(RelativeDates.resolve(event, workdays(-1), null))
					.isEqualTo(LocalDate.of(2027, 1, 1));
			assertThat(RelativeDates.resolve(event, workdays(-1), withNewYear))
					.isEqualTo(LocalDate.of(2026, 12, 31));
		}

		@Test
		@DisplayName("a calendar with no working days at all gives up instead of spinning")
		void aDegenerateCalendarTerminates() {
			assertThatThrownBy(() -> RelativeDates.resolve(THURSDAY, workdays(1), date -> false))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("no working day");
		}
	}

	@Nested
	@DisplayName("the absent cases")
	class Absent {

		@Test
		@DisplayName("no event date gives no date, and loses nothing")
		void withoutAnAnchorThereIsNoDate() {
			assertThat(RelativeDates.resolve(null, days(-28), null)).isNull();
		}

		@Test
		@DisplayName("no offset gives no date")
		void withoutAnOffsetThereIsNoDate() {
			assertThat(RelativeDates.resolve(THURSDAY, null, null)).isNull();
		}
	}

	@Nested
	@DisplayName("the stored shape")
	class Shape {

		@Test
		@DisplayName("a value that arrives without its enums reads as calendar days")
		void theEnumsAreFilledIn() {
			RelativeDate offset = new RelativeDate(-14, null, null);

			assertThat(offset.unit()).isEqualTo(RelativeDate.Unit.DAYS);
			assertThat(offset.basis()).isEqualTo(RelativeDate.Basis.CALENDAR);
			assertThat(RelativeDates.resolve(THURSDAY, offset, null))
					.isEqualTo(LocalDate.of(2026, 10, 29));
		}

		@Test
		@DisplayName("the bounds are the same distance in both units")
		void theLimitsHold() {
			assertThat(days(730).withinLimits()).isTrue();
			assertThat(days(-731).withinLimits()).isFalse();
			assertThat(weeks(104).withinLimits()).isTrue();
			assertThat(weeks(-105).withinLimits()).isFalse();
			// A week counted in days is seven of them, which is what the copy and the preview
			// both rely on.
			assertThat(weeks(-4).days()).isEqualTo(-28);
		}
	}
}
