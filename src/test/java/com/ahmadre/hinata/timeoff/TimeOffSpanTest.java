package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a stretch of dates is worth in working days.
 *
 * <p>Pure, so these are the cheap tests that pin the arithmetic three other places depend on: the
 * form previewing, the submission freezing and the booking taking.
 */
class TimeOffSpanTest {

	private static final int DAY = TimeOffSpan.DAY;

	/** Monday the 1st of June 2026. A week that starts on a Monday, so the weekend is the 6th. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);

	/** The ordinary week: Monday to Friday. */
	private static final Predicate<LocalDate> WEEKDAYS =
			day -> day.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue();

	@Test
	@DisplayName("a full working week costs five days")
	void aWeekIsFiveDays() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(4), WEEKDAYS, Map.of(), DAY, DAY);

		assertThat(span.milliDays()).isEqualTo(5 * DAY);
		assertThat(span.workingDays()).isEqualTo(5);
		assertThat(span.daysOff()).isZero();
	}

	@Test
	@DisplayName("a weekend inside the span is not leave")
	void theWeekendCostsNothing() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(13), WEEKDAYS, Map.of(), DAY, DAY);

		// Ten working days across two weeks, and the four weekend days are simply not counted.
		assertThat(span.milliDays()).isEqualTo(10 * DAY);
		assertThat(span.daysOff()).isEqualTo(4);
	}

	@Test
	@DisplayName("a public holiday inside leave was never leave (§ 3 Abs. 1 BUrlG)")
	void aHolidayCostsNothing() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(4), WEEKDAYS,
				Map.of(MONDAY.plusDays(2), false), DAY, DAY);

		assertThat(span.milliDays()).isEqualTo(4 * DAY);
		assertThat(span.workingDays()).isEqualTo(4);
		assertThat(span.holidays()).isEqualTo(1);
	}

	@Test
	@DisplayName("half a holiday leaves half a working day standing, and that half is leave")
	void aHalfHolidayIsWorthHalfADay() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(1), WEEKDAYS,
				Map.of(MONDAY.plusDays(1), true), DAY, DAY);

		assertThat(span.milliDays()).isEqualTo(DAY + DAY / 2);
	}

	@Test
	@DisplayName("only the edges can be partial")
	void theMiddleIsAlwaysAWholeDay() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(2), WEEKDAYS, Map.of(),
				DAY / 2, DAY / 2);

		// Half on the Monday, whole on the Tuesday, half on the Wednesday.
		assertThat(span.milliDays()).isEqualTo(2 * DAY);
	}

	@Test
	@DisplayName("a one-day span reads its portion from the first day alone")
	void oneDayUsesTheFirstPortion() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY, WEEKDAYS, Map.of(), DAY / 2, DAY);

		// The same date is also the last day; taking the larger of the two would quietly turn a
		// half day off into a whole one.
		assertThat(span.milliDays()).isEqualTo(DAY / 2);
	}

	@Test
	@DisplayName("half a day of leave on a half holiday is a quarter, never a half")
	void aPortionNeverExceedsTheDayItIsPartOf() {
		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY, WEEKDAYS,
				Map.of(MONDAY, true), DAY / 2, DAY);

		assertThat(span.milliDays()).isEqualTo(DAY / 4);
	}

	@Test
	@DisplayName("a day the pattern leaves empty is worth nothing, whatever the hours elsewhere")
	void anEmptyPartTimeDayCostsNothing() {
		// Four days a week: nothing on the Friday.
		Predicate<LocalDate> fourDays = day -> day.getDayOfWeek().getValue() <= DayOfWeek.THURSDAY.getValue();

		TimeOffSpan.Result span = TimeOffSpan.of(MONDAY, MONDAY.plusDays(4), fourDays, Map.of(), DAY, DAY);

		// Days and never minutes: somebody on four eight-hour days spends four days on this week,
		// exactly as somebody on five six-hour days would spend five (§ 3 BUrlG).
		assertThat(span.milliDays()).isEqualTo(4 * DAY);
	}

	@Test
	void anEndBeforeItsStartIsRefused() {
		assertThatThrownBy(() -> TimeOffSpan.of(MONDAY.plusDays(1), MONDAY, WEEKDAYS, Map.of(), DAY, DAY))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.spanInvalid");
	}

	@Test
	void aSpanLongerThanAYearIsRefused() {
		assertThatThrownBy(() -> TimeOffSpan.of(MONDAY, MONDAY.plusDays(TimeOffSpan.SPAN_DAYS_MAX),
				WEEKDAYS, Map.of(), DAY, DAY))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.timeOff.spanTooLong");
	}
}
