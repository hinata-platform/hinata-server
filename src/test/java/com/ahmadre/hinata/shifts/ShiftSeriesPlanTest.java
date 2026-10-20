package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic of a series (HIN-161) on the wall clock of Europe/Berlin, across both changes of
 * the clock, and the limits of its shape.
 */
class ShiftSeriesPlanTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

	@Test
	void aWeeklySundayStaysAt18OClockAcrossBothChanges() {
		ShiftSeries series = series("FREQ=WEEKLY", LocalDateTime.of(2026, 10, 18, 18, 0), 120);
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2026, 10, 18)).startsAt())
				.isEqualTo(Instant.parse("2026-10-18T16:00:00Z"));
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2026, 10, 25)).startsAt())
				.isEqualTo(Instant.parse("2026-10-25T17:00:00Z"));
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2027, 3, 21)).startsAt())
				.isEqualTo(Instant.parse("2027-03-21T17:00:00Z"));
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2027, 3, 28)).startsAt())
				.isEqualTo(Instant.parse("2027-03-28T16:00:00Z"));
		assertThat(ShiftSeriesPlan.dates(series, LocalDate.of(2027, 3, 20), LocalDate.of(2027, 4, 4)))
				.containsExactly(LocalDate.of(2027, 3, 21), LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 4));
	}

	@Test
	void aNightShiftOverTheNightTheClocksGoBackLastsNineHours() {
		ShiftSeries series = series("FREQ=WEEKLY", LocalDateTime.of(2026, 10, 17, 20, 0), 14 * 60);
		ShiftTemplate night = ShiftTemplate.builder().key("night").dayOffset(0).start(LocalTime.of(22, 0))
				.end(LocalTime.of(6, 0)).requiredHelpers(1).build();
		ShiftSeriesPlan.ShiftTimes before = ShiftSeriesPlan.shiftTimes(series, night, LocalDate.of(2026, 10, 17));
		ShiftSeriesPlan.ShiftTimes over = ShiftSeriesPlan.shiftTimes(series, night, LocalDate.of(2026, 10, 24));
		assertThat(Duration.between(before.startsAt(), before.endsAt())).isEqualTo(Duration.ofHours(8));
		assertThat(over.startsAt()).isEqualTo(Instant.parse("2026-10-24T20:00:00Z"));
		assertThat(over.endsAt()).isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
		assertThat(Duration.between(over.startsAt(), over.endsAt())).isEqualTo(Duration.ofHours(9));
	}

	@Test
	void aWallClockTimeInTheGapIsReadWithTheOffsetFromBeforeIt() {
		ShiftSeries series = series("FREQ=DAILY", LocalDateTime.of(2026, 3, 28, 2, 30), 60);
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2026, 3, 28)).startsAt())
				.isEqualTo(Instant.parse("2026-03-28T01:30:00Z"));
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2026, 3, 29)).startsAt())
				.isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
		assertThat(ShiftSeriesPlan.occurrence(series, LocalDate.of(2026, 3, 30)).startsAt())
				.isEqualTo(Instant.parse("2026-03-30T00:30:00Z"));
	}

	@Test
	void theHorizonEndsAtNinetyDaysOrTheFiftySecondOccurrence() {
		ShiftSeries daily = series("FREQ=DAILY", TODAY.atTime(9, 0), 60);
		ShiftSeriesPlan.Horizon first = ShiftSeriesPlan.horizon(daily, TODAY);
		assertThat(first.dates()).hasSize(52);
		assertThat(first.until()).isEqualTo(TODAY.plusDays(51));
		daily.setMaterializedUntil(first.until());
		ShiftSeriesPlan.Horizon second = ShiftSeriesPlan.horizon(daily, TODAY);
		assertThat(second.dates()).hasSize(39);
		assertThat(second.until()).isEqualTo(TODAY.plusDays(90));

		ShiftSeries weekly = series("FREQ=WEEKLY", TODAY.atTime(18, 0), 60);
		assertThat(ShiftSeriesPlan.horizon(weekly, TODAY).dates()).hasSize(13);

		// After months off, the run starts at today, never in the past.
		daily.setMaterializedUntil(TODAY.minusMonths(4));
		assertThat(ShiftSeriesPlan.horizon(daily, TODAY).dates().get(0)).isEqualTo(TODAY);
	}

	@Test
	void countAndUntilEndTheSeries() {
		ShiftSeries counted = series("FREQ=WEEKLY;COUNT=3", TODAY.atTime(18, 0), 60);
		ShiftSeriesPlan.Horizon horizon = ShiftSeriesPlan.horizon(counted, TODAY);
		assertThat(horizon.dates()).hasSize(3);
		assertThat(ShiftSeriesPlan.endsBy(counted, horizon.until())).isTrue();
		assertThat(ShiftSeriesPlan.countBefore(counted, TODAY.plusWeeks(2))).isEqualTo(2);

		ShiftSeries until = series("FREQ=DAILY;UNTIL=20261025", TODAY.atTime(18, 0), 60);
		assertThat(ShiftSeriesPlan.horizon(until, TODAY).dates()).hasSize(6);
		assertThat(ShiftSeriesPlan.endsBy(until, LocalDate.of(2026, 10, 25))).isTrue();
		assertThat(ShiftSeriesPlan.endsBy(series("FREQ=DAILY", TODAY.atTime(9, 0), 60), TODAY.plusDays(90))).isFalse();
	}

	@Test
	void closingAndCountingOnRewriteTheRule() {
		assertThat(ShiftSeriesPlan.closedOn("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10", LocalDate.of(2026, 11, 1)))
				.isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261101");
		assertThat(ShiftSeriesPlan.withCount("FREQ=WEEKLY;COUNT=10", 7)).isEqualTo("FREQ=WEEKLY;COUNT=7");
		assertThat(ShiftSeriesPlan.withCount("FREQ=WEEKLY", 7)).isEqualTo("FREQ=WEEKLY");
	}

	@Test
	void theShapeIsCheckedLikeEventsAndShifts() {
		assertThatThrownBy(() -> validate(series("FREQ=YEARLY", TODAY.atTime(9, 0), 60)))
				.hasMessage("error.shifts.rruleUnsupported");
		assertThatThrownBy(() -> validate(series("FREQ=WEEKLY;INTERVAL=101", TODAY.atTime(9, 0), 60)))
				.hasMessage("error.shifts.rruleUnsupported");
		assertThatThrownBy(() -> validate(series("FREQ=WEEKLY", TODAY.minusYears(1).minusDays(1).atTime(9, 0), 60)))
				.hasMessage("error.shifts.seriesStartOutOfRange");
		assertThatThrownBy(() -> validate(series("FREQ=WEEKLY", TODAY.plusYears(2).plusDays(1).atTime(9, 0), 60)))
				.hasMessage("error.shifts.seriesStartOutOfRange");
		assertThatThrownBy(() -> validate(series("FREQ=WEEKLY", TODAY.atTime(9, 0), 15 * 24 * 60)))
				.hasMessage("error.shifts.eventTooLong");

		ShiftSeries outside = series("FREQ=WEEKLY", TODAY.atTime(9, 0), 120);
		outside.getShifts().add(ShiftTemplate.builder().start(LocalTime.of(10, 0)).end(LocalTime.of(12, 0)).build());
		assertThatThrownBy(() -> validate(outside)).hasMessage("error.shifts.shiftOutsideEvent");

		ShiftSeries crowded = series("FREQ=WEEKLY", TODAY.atTime(9, 0), 120);
		crowded.getShifts().add(ShiftTemplate.builder().start(LocalTime.of(9, 0)).end(LocalTime.of(10, 0))
				.requiredHelpers(3).maxHelpers(2).build());
		assertThatThrownBy(() -> validate(crowded)).hasMessage("error.shifts.maxBelowRequired");

		ShiftSeries many = series("FREQ=WEEKLY", TODAY.atTime(9, 0), 120);
		for (int i = 0; i <= ShiftSeriesPlan.TEMPLATES_MAX; i++) {
			many.getShifts().add(ShiftTemplate.builder().start(LocalTime.of(9, 0)).end(LocalTime.of(10, 0)).build());
		}
		assertThatThrownBy(() -> validate(many)).hasMessage("error.shifts.seriesTooManyShifts");

		ShiftSeries fine = series("FREQ=WEEKLY", TODAY.atTime(9, 0), 120);
		fine.getShifts().add(ShiftTemplate.builder().title(" Theke ").start(LocalTime.of(9, 0))
				.end(LocalTime.of(10, 0)).build());
		validate(fine);
		assertThat(fine.getShifts().get(0).getKey()).isNotBlank();
		assertThat(fine.getShifts().get(0).getTitle()).isEqualTo("Theke");
	}

	@Test
	void aHorizonOverTwoThousandShiftsIsRefused() {
		ShiftSeries daily = series("FREQ=DAILY", TODAY.atTime(9, 0), 600);
		for (int i = 0; i < 39; i++) {
			daily.getShifts().add(ShiftTemplate.builder().key("k" + i).start(LocalTime.of(9, 0))
					.end(LocalTime.of(10, 0)).build());
		}
		int dates = ShiftSeriesPlan.horizon(daily, TODAY).dates().size();
		assertThatThrownBy(() -> ShiftSeriesPlan.assertHorizonFits(daily, dates))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.getMessageKey()).isEqualTo("error.shifts.seriesTooLarge"));
		daily.getShifts().remove(0);
		ShiftSeriesPlan.assertHorizonFits(daily, dates);
	}

	private static void validate(ShiftSeries series) {
		ShiftSeriesPlan.validate(series, TODAY);
	}

	private static ShiftSeries series(String rrule, LocalDateTime firstStart, int minutes) {
		return ShiftSeries.builder().title("Bereitschaft").timezone("Europe/Berlin").rrule(rrule)
				.firstStart(firstStart).durationMinutes(minutes).shifts(new ArrayList<>(List.of()))
				.exdates(new ArrayList<>()).build();
	}
}
