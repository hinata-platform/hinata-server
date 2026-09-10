package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which days a period is made of.
 *
 * <p>Every assertion here is a date somebody would otherwise get wrong once a
 * year and never notice: the end of a short month, the 29th of February, the week
 * that straddles New Year, the quarter boundary, and an anchor date an operator
 * set in the future. A payroll period that is a day short is a wrong answer
 * wearing the shape of a right one, so the edges are the test.
 */
class ApprovalPeriodsTest {

	private static TimeTrackingSettings.ApprovalPeriod policy(TimePolicy.ApprovalPeriod type,
			DayOfWeek weekStart, LocalDate anchor, Integer days) {
		return new TimeTrackingSettings.ApprovalPeriod(type, weekStart, anchor, days);
	}

	private static TimeTrackingSettings.ApprovalPeriod monthly() {
		return policy(TimePolicy.ApprovalPeriod.MONTHLY, DayOfWeek.MONDAY, null, null);
	}

	// --- monthly -----------------------------------------------------------------

	@Test
	void aMonthEndsOnItsOwnLastDayWhateverThatIs() {
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 2, 17), monthly()))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 2, 1),
						LocalDate.of(2026, 2, 28), TimePolicy.ApprovalPeriod.MONTHLY));
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 4, 30), monthly()).end())
				.isEqualTo(LocalDate.of(2026, 4, 30));
	}

	@Test
	void aLeapFebruaryHasTwentyNineDays() {
		// 2028 rather than 2026: the one the arithmetic could get wrong is the leap
		// year, and a fixture in a non-leap year would pass either way.
		ApprovalPeriods.Period february =
				ApprovalPeriods.periodFor(LocalDate.of(2028, 2, 10), monthly());
		assertThat(february.end()).isEqualTo(LocalDate.of(2028, 2, 29));
		assertThat(february.days()).isEqualTo(29);
	}

	// --- semi-monthly -------------------------------------------------------------

	@Test
	void aSemiMonthlyRhythmSplitsOnTheFifteenthAndKeepsTheRemainder() {
		TimeTrackingSettings.ApprovalPeriod policy =
				policy(TimePolicy.ApprovalPeriod.SEMI_MONTHLY, DayOfWeek.MONDAY, null, null);
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 15), policy))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 3, 1),
						LocalDate.of(2026, 3, 15), TimePolicy.ApprovalPeriod.SEMI_MONTHLY));
		// The 16th starts the second half, and the second half is whatever is left —
		// 13 days in February, 16 in March.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 16), policy).end())
				.isEqualTo(LocalDate.of(2026, 3, 31));
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 2, 20), policy).end())
				.isEqualTo(LocalDate.of(2026, 2, 28));
	}

	// --- weekly ------------------------------------------------------------------

	@Test
	void aWeekStartsOnTheConfiguredDayAndCanStraddleNewYear() {
		TimeTrackingSettings.ApprovalPeriod monday =
				policy(TimePolicy.ApprovalPeriod.WEEKLY, DayOfWeek.MONDAY, null, null);
		// 2027-01-01 is a Friday, so its Monday week opens in the old year.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2027, 1, 1), monday))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 12, 28),
						LocalDate.of(2027, 1, 3), TimePolicy.ApprovalPeriod.WEEKLY));
	}

	@Test
	void aSundayWeekStartMovesTheWholeGridAndNotJustItsLabel() {
		TimeTrackingSettings.ApprovalPeriod sunday =
				policy(TimePolicy.ApprovalPeriod.WEEKLY, DayOfWeek.SUNDAY, null, null);
		// 2026-09-07 is a Monday; with weeks starting on Sunday it belongs to the
		// week that opened the day before.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 7), sunday))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 9, 6),
						LocalDate.of(2026, 9, 12), TimePolicy.ApprovalPeriod.WEEKLY));
		// And a Sunday is the first day of its own week, not the last of the one before.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 6), sunday).start())
				.isEqualTo(LocalDate.of(2026, 9, 6));
	}

	// --- quarterly ----------------------------------------------------------------

	@Test
	void aQuarterRunsFromItsFirstMonthToTheLastDayOfItsThird() {
		TimeTrackingSettings.ApprovalPeriod policy =
				policy(TimePolicy.ApprovalPeriod.QUARTERLY, DayOfWeek.MONDAY, null, null);
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 30), policy))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 7, 1),
						LocalDate.of(2026, 9, 30), TimePolicy.ApprovalPeriod.QUARTERLY));
		// Both boundaries of the same quarter land in it, and the next day does not.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 10, 1), policy).start())
				.isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 1, 1), policy).end())
				.isEqualTo(LocalDate.of(2026, 3, 31));
	}

	@Test
	void noGridPeriodIsEverLongerThanASubmissionMayBe() {
		// 92 is the longest calendar quarter (Jul–Sep), which is exactly why the
		// submission ceiling is 92 and not 90: a grid that could cut a period
		// nobody is allowed to hand in would be a rhythm with a hole in it.
		for (int month = 1; month <= 12; month += 3) {
			assertThat(ApprovalPeriods.periodFor(LocalDate.of(2028, month, 1),
							policy(TimePolicy.ApprovalPeriod.QUARTERLY, null, null, null)).days())
					.isLessThanOrEqualTo(TimePolicy.PERIOD_MAX_DAYS);
		}
	}

	// --- counted rhythms -----------------------------------------------------------

	@Test
	void aBiweeklyGridCountsFromItsAnchorInBothDirections() {
		TimeTrackingSettings.ApprovalPeriod policy = policy(TimePolicy.ApprovalPeriod.BIWEEKLY,
				DayOfWeek.MONDAY, LocalDate.of(2026, 9, 7), null);
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 20), policy))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 9, 7),
						LocalDate.of(2026, 9, 20), TimePolicy.ApprovalPeriod.BIWEEKLY));
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 21), policy).start())
				.isEqualTo(LocalDate.of(2026, 9, 21));
		// Before the anchor: floorDiv is what makes this work. Plain integer
		// division rounds -1/14 towards zero and would put the day before the
		// anchor in the anchor's own block.
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 6), policy))
				.isEqualTo(new ApprovalPeriods.Period(LocalDate.of(2026, 8, 24),
						LocalDate.of(2026, 9, 6), TimePolicy.ApprovalPeriod.BIWEEKLY));
	}

	@Test
	void anAnchorInTheFutureStillAnswersAboutToday() {
		// An operator who sets the rhythm to start on the first of next month must
		// still be able to look at last week.
		TimeTrackingSettings.ApprovalPeriod policy = policy(TimePolicy.ApprovalPeriod.CUSTOM_DAYS,
				DayOfWeek.MONDAY, LocalDate.of(2027, 1, 1), 10);
		ApprovalPeriods.Period period =
				ApprovalPeriods.periodFor(LocalDate.of(2026, 12, 30), policy);
		assertThat(period.start()).isEqualTo(LocalDate.of(2026, 12, 22));
		assertThat(period.end()).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(period.days()).isEqualTo(10);
	}

	@Test
	void aCountedRhythmBeginsExactlyOnItsAnchorAndIsNotSnappedToTheWeekStart() {
		// The anchor is the more specific of the two statements. Moving it to the
		// nearest Monday behind the operator's back would shift every period they
		// previewed before saving. 2026-09-09 is a Wednesday.
		TimeTrackingSettings.ApprovalPeriod policy = policy(TimePolicy.ApprovalPeriod.BIWEEKLY,
				DayOfWeek.MONDAY, LocalDate.of(2026, 9, 9), null);
		assertThat(ApprovalPeriods.periodFor(LocalDate.of(2026, 9, 9), policy).start())
				.isEqualTo(LocalDate.of(2026, 9, 9));
	}

	// --- enumerating ----------------------------------------------------------------

	@Test
	void everyPeriodTouchingTheWindowComesBackOnceAndInOrder() {
		List<ApprovalPeriods.Period> periods = ApprovalPeriods.periodsIn(
				LocalDate.of(2026, 2, 20), LocalDate.of(2026, 4, 3), monthly());
		assertThat(periods).extracting(ApprovalPeriods.Period::start)
				.containsExactly(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1),
						LocalDate.of(2026, 4, 1));
	}

	@Test
	void theEnumerationIsCappedRatherThanUnbounded() {
		// A daily rhythm over four years is the shape of an accident, and an
		// unbounded loop over a span is what a later caller inherits without
		// noticing.
		assertThat(ApprovalPeriods.periodsIn(LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
						policy(TimePolicy.ApprovalPeriod.CUSTOM_DAYS, null,
								LocalDate.of(2026, 1, 1), 1)))
				.hasSize(ApprovalPeriods.MAX_PERIODS);
	}

	@Test
	void anInvertedOrEmptyWindowAnswersWithNothingRatherThanThrowing() {
		assertThat(ApprovalPeriods.periodsIn(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1),
				monthly())).isEmpty();
		assertThat(ApprovalPeriods.periodsIn(null, LocalDate.of(2026, 3, 1), monthly())).isEmpty();
	}

	// --- the grid check ---------------------------------------------------------------

	@Test
	void onlyAnExactPeriodMatchesTheGrid() {
		assertThat(ApprovalPeriods.matchesGrid(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
				monthly())).isTrue();
		// One day short at either end is not a period, and neither is two months.
		assertThat(ApprovalPeriods.matchesGrid(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 31),
				monthly())).isFalse();
		assertThat(ApprovalPeriods.matchesGrid(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30),
				monthly())).isFalse();
		assertThat(ApprovalPeriods.matchesGrid(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30),
				monthly())).isFalse();
	}

	// --- free --------------------------------------------------------------------------

	@Test
	void aFreeRhythmHasNoGridAtAll() {
		TimeTrackingSettings.ApprovalPeriod free =
				policy(TimePolicy.ApprovalPeriod.FREE, null, null, null);
		assertThat(ApprovalPeriods.hasGrid(free)).isFalse();
		assertThat(ApprovalPeriods.periodsIn(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
				free)).isEmpty();
		// Any span matches a rhythm with no grid — the length and overlap rules are
		// the service's, against the collection, which this function cannot see.
		assertThat(ApprovalPeriods.matchesGrid(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9),
				free)).isTrue();
		// And asking for "the period containing this day" is refused rather than
		// answered with an invented span a client would then draw arrows around.
		assertThatThrownBy(() -> ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 2), free))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRhythmMissingWhatItsTypeNeedsFailsLoudlyRatherThanPickingAValue() {
		// ApprovalPeriodConsistent refuses these on the way in, so reaching here is
		// a bug — and a bug that silently defaulted the anchor to the epoch would
		// cut periods nobody could explain.
		assertThatThrownBy(() -> ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 2),
				policy(TimePolicy.ApprovalPeriod.BIWEEKLY, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 2),
				policy(TimePolicy.ApprovalPeriod.CUSTOM_DAYS, null, LocalDate.of(2026, 1, 1), null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ApprovalPeriods.periodFor(LocalDate.of(2026, 3, 2), null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
