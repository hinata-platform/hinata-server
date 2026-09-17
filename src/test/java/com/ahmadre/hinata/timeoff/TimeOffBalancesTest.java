package com.ahmadre.hinata.timeoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §§ 4 and 5 BUrlG as arithmetic, with the cases that are easy to get wrong.
 *
 * <p>The numbers are in thousandths of a working day: twenty days is 20000, half a day is 500.
 */
class TimeOffBalancesTest {

	private static final MonthDay CALENDAR_YEAR = MonthDay.of(1, 1);
	private static final int TWENTY_DAYS = 20 * TimeOffType.DAY;

	private static TimeOffBalances.Rules vacation() {
		return new TimeOffBalances.Rules(TWENTY_DAYS, CALENDAR_YEAR, 6, true, true);
	}

	@Nested
	@DisplayName("the leave year")
	class LeaveYear {

		@Test
		void aCalendarYearRunsFromJanuaryToDecember() {
			assertThat(TimeOffBalances.yearStart(2026, CALENDAR_YEAR)).isEqualTo(LocalDate.of(2026, 1, 1));
			assertThat(TimeOffBalances.yearEnd(2026, CALENDAR_YEAR)).isEqualTo(LocalDate.of(2026, 12, 31));
			assertThat(TimeOffBalances.leaveYearOf(LocalDate.of(2026, 12, 31), CALENDAR_YEAR)).isEqualTo(2026);
		}

		@Test
		void aYearAnchoredInAprilIsNamedAfterTheYearItStartsIn() {
			MonthDay april = MonthDay.of(4, 1);

			assertThat(TimeOffBalances.yearEnd(2026, april)).isEqualTo(LocalDate.of(2027, 3, 31));
			// March 2027 still belongs to the year that started in April 2026 — otherwise "the 2026
			// balance" would mean two different things in the months people ask about it.
			assertThat(TimeOffBalances.leaveYearOf(LocalDate.of(2027, 3, 31), april)).isEqualTo(2026);
			assertThat(TimeOffBalances.leaveYearOf(LocalDate.of(2027, 4, 1), april)).isEqualTo(2027);
		}
	}

	@Nested
	@DisplayName("joining")
	class Joining {

		@Test
		@DisplayName("1 January: the whole year")
		void joiningOnTheFirstDayEarnsTheWholeYear() {
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2026, 1, 1), null, 2026);

			assertThat(accrued.milliDays()).isEqualTo(TWENTY_DAYS);
			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.FULL);
		}

		@Test
		@DisplayName("15 July: five twelfths, and 8.33 days stays 8.33")
		void joiningInJulyEarnsTwelfthsForTheFullMonths() {
			// The waiting period ends on 15 January 2027, so 2026 is a part year (§ 5 Abs. 1 lit. a).
			// July is not a full month of employment; August to December are five.
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2026, 7, 15), null, 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.WAITING_PERIOD);
			assertThat(accrued.monthsCounted()).isEqualTo(5);
			// 20 × 5/12 = 8.333…, and a third of a day is less than half, so it stays a fraction.
			assertThat(accrued.milliDays()).isEqualTo(8_333);
		}

		@Test
		@DisplayName("30 November: one twelfth, rounded up to a whole day")
		void aFractionOfAtLeastHalfADayBecomesAWholeDay() {
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2026, 11, 30), null, 2026);

			assertThat(accrued.monthsCounted()).isEqualTo(1);
			// 20 × 1/12 = 1.667, and two thirds of a day is at least half, so § 5 Abs. 2 rounds up.
			assertThat(accrued.milliDays()).isEqualTo(2 * TimeOffType.DAY);
		}

		@Test
		@DisplayName("1 March: the whole year, because the waiting period ends inside it")
		void finishingTheWaitingPeriodInsideTheYearEarnsItWhole() {
			// The case a naive "months employed ÷ 12" gets wrong: ten twelfths would be 16.67 days,
			// and § 4 BUrlG grants the full entitlement the moment the waiting period is over.
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2026, 3, 1), null, 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.FULL);
			assertThat(accrued.milliDays()).isEqualTo(TWENTY_DAYS);
		}

		@Test
		void aYearBeforeSomebodyJoinedIsWorthNothing() {
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2027, 2, 1), null, 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.NOT_EMPLOYED);
			assertThat(accrued.milliDays()).isZero();
		}

		@Test
		void withNoJoiningDateTheYearIsGrantedInFull() {
			// An instance that keeps no employment dates still has to answer, and the answer that
			// cannot short-change anybody is the whole year.
			TimeOffBalances.Accrued accrued = TimeOffBalances.accrue(vacation(), null, null, 2026);

			assertThat(accrued.milliDays()).isEqualTo(TWENTY_DAYS);
		}
	}

	@Nested
	@DisplayName("leaving")
	class Leaving {

		private static final LocalDate LONG_AGO = LocalDate.of(2020, 1, 1);

		@Test
		@DisplayName("in the first half: twelfths (§ 5 Abs. 1 lit. c)")
		void leavingInTheFirstHalfEarnsTwelfths() {
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LONG_AGO, LocalDate.of(2026, 3, 31), 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.LEFT_IN_FIRST_HALF);
			assertThat(accrued.monthsCounted()).isEqualTo(3);
			assertThat(accrued.milliDays()).isEqualTo(5 * TimeOffType.DAY);
		}

		@Test
		@DisplayName("in the second half: the whole year")
		void leavingInTheSecondHalfKeepsTheWholeYear() {
			TimeOffBalances.Accrued accrued =
					TimeOffBalances.accrue(vacation(), LONG_AGO, LocalDate.of(2026, 8, 31), 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.FULL);
			assertThat(accrued.milliDays()).isEqualTo(TWENTY_DAYS);
		}

		@Test
		@DisplayName("before the waiting period is over: twelfths (§ 5 Abs. 1 lit. b)")
		void leavingDuringTheWaitingPeriodEarnsTwelfths() {
			TimeOffBalances.Accrued accrued = TimeOffBalances.accrue(vacation(),
					LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30), 2026);

			assertThat(accrued.reason()).isEqualTo(TimeOffBalances.Reason.LEFT_IN_WAITING_PERIOD);
			assertThat(accrued.monthsCounted()).isEqualTo(3);
			assertThat(accrued.milliDays()).isEqualTo(5 * TimeOffType.DAY);
		}

		@Test
		void aTypeThatDoesNotProrateGrantsTheYearAnyway() {
			// Contractual leave an employer grants in full whenever somebody is on the books.
			TimeOffBalances.Rules noProration =
					new TimeOffBalances.Rules(TWENTY_DAYS, CALENDAR_YEAR, 0, false, false);

			TimeOffBalances.Accrued accrued = TimeOffBalances.accrue(noProration,
					LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), 2026);

			assertThat(accrued.milliDays()).isEqualTo(TWENTY_DAYS);
		}
	}

	@Nested
	@DisplayName("twelfths")
	class Twelfths {

		@Test
		void roundUpFromHalfADayAndNeverDown() {
			assertThat(TimeOffBalances.twelfths(TWENTY_DAYS, 5)).isEqualTo(8_333);
			// 20 × 7/12 = 11.667 ⇒ twelve whole days.
			assertThat(TimeOffBalances.twelfths(TWENTY_DAYS, 7)).isEqualTo(12 * TimeOffType.DAY);
			assertThat(TimeOffBalances.twelfths(TWENTY_DAYS, 12)).isEqualTo(TWENTY_DAYS);
			assertThat(TimeOffBalances.twelfths(TWENTY_DAYS, 0)).isZero();
			// Rounding up may never overshoot the year itself.
			assertThat(TimeOffBalances.twelfths(TWENTY_DAYS, 11)).isLessThanOrEqualTo(TWENTY_DAYS);
		}

		@Test
		void aMonthIsFullOnlyWhenItWasWorkedEndToEnd() {
			assertThat(TimeOffBalances.fullMonths(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31)))
					.isEqualTo(5);
			assertThat(TimeOffBalances.fullMonths(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 12, 31)))
					.isEqualTo(4);
			assertThat(TimeOffBalances.fullMonths(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 8, 1)))
					.isZero();
		}
	}

	@Nested
	@DisplayName("accruing through the year")
	class Monthly {

		@Test
		void anAnnualTypeEarnsItAllOnTheAnchorDay() {
			TimeOffBalances.Accrued year =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2020, 1, 1), null, 2026);

			assertThat(TimeOffBalances.accruedBy(year, TimeOffType.Accrual.ANNUAL, CALENDAR_YEAR, 2026,
					LocalDate.of(2026, 1, 2))).isEqualTo(TWENTY_DAYS);
		}

		@Test
		void aMonthlyTypeEarnsATwelfthPerFullMonthAndNeverMoreThanTheYear() {
			TimeOffBalances.Accrued year =
					TimeOffBalances.accrue(vacation(), LocalDate.of(2020, 1, 1), null, 2026);

			assertThat(TimeOffBalances.accruedBy(year, TimeOffType.Accrual.MONTHLY, CALENDAR_YEAR, 2026,
					LocalDate.of(2026, 3, 31))).isEqualTo(5 * TimeOffType.DAY);
			assertThat(TimeOffBalances.accruedBy(year, TimeOffType.Accrual.MONTHLY, CALENDAR_YEAR, 2026,
					LocalDate.of(2025, 12, 31))).isZero();
			assertThat(TimeOffBalances.accruedBy(year, TimeOffType.Accrual.MONTHLY, CALENDAR_YEAR, 2026,
					LocalDate.of(2027, 6, 1))).isEqualTo(TWENTY_DAYS);
		}
	}

	@Nested
	@DisplayName("the statutory floor")
	class Floor {

		@Test
		void fourWeeksOfWhateverTheWeekLooksLike() {
			assertThat(TimeOffLegalFloor.minimumMilliDays(6)).isEqualTo(24 * TimeOffType.DAY);
			assertThat(TimeOffLegalFloor.minimumMilliDays(5)).isEqualTo(20 * TimeOffType.DAY);
			assertThat(TimeOffLegalFloor.minimumMilliDays(4)).isEqualTo(16 * TimeOffType.DAY);
		}

		@Test
		void onlyPaidVacationWithABalanceHasAFloorToFallBelow() {
			TimeOffType vacation = TimeOffType.builder().kind(TimeOffType.Kind.VACATION).paid(true)
					.countsAgainstBalance(true).allowanceMilliDays(18 * TimeOffType.DAY).build();
			TimeOffType unpaid = TimeOffType.builder().kind(TimeOffType.Kind.UNPAID).paid(false)
					.unlimited(true).build();

			assertThat(TimeOffLegalFloor.fallsShort(vacation, 5)).isTrue();
			assertThat(TimeOffLegalFloor.fallsShort(vacation, 4)).isFalse();
			assertThat(TimeOffLegalFloor.fallsShort(unpaid, 5)).isFalse();
		}
	}
}
