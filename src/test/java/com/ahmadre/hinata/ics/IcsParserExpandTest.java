package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IcsParser#expand} (HIN-161): the series of one's own a project plans, as wall-clock times
 * in the series' zone, over the subset of RFC 5545 a series of shifts may use.
 */
class IcsParserExpandTest {

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	@Test
	void dailyWithoutAnEndRunsAsFarAsAsked() {
		List<ZonedDateTime> days = IcsParser.expand("FREQ=DAILY", LocalDateTime.of(2026, 10, 20, 9, 0), BERLIN,
				LocalDate.of(2026, 10, 20), LocalDate.of(2027, 1, 17));
		assertThat(days).hasSize(90);
		assertThat(days).allSatisfy(day -> assertThat(day.toLocalTime().getHour()).isEqualTo(9));
	}

	@Test
	void weeklyOnSeveralDays() {
		List<ZonedDateTime> days = IcsParser.expand("FREQ=WEEKLY;BYDAY=MO,WE", LocalDateTime.of(2026, 10, 19, 18, 0),
				BERLIN, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 1));
		assertThat(days).extracting(ZonedDateTime::toLocalDate).containsExactly(LocalDate.of(2026, 10, 19),
				LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28));
	}

	@Test
	void monthlyOnADayWithAnInterval() {
		List<ZonedDateTime> days = IcsParser.expand("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=15",
				LocalDateTime.of(2026, 11, 15, 10, 0), BERLIN, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 6, 1));
		assertThat(days).extracting(ZonedDateTime::toLocalDate).containsExactly(LocalDate.of(2026, 11, 15),
				LocalDate.of(2027, 1, 15), LocalDate.of(2027, 3, 15), LocalDate.of(2027, 5, 15));
	}

	@Test
	void countCountsTheFirstOccurrenceAndFromTheStartOfTheSeries() {
		String rule = "FREQ=WEEKLY;COUNT=5";
		LocalDateTime first = LocalDateTime.of(2026, 10, 19, 18, 0);
		assertThat(IcsParser.expand(rule, first, BERLIN, first.toLocalDate(), LocalDate.of(2027, 12, 31))).hasSize(5);
		// A window that starts later still counts from the first occurrence.
		assertThat(IcsParser.expand(rule, first, BERLIN, LocalDate.of(2026, 11, 2), LocalDate.of(2027, 12, 31)))
				.extracting(ZonedDateTime::toLocalDate)
				.containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16));
	}

	@Test
	void untilIsADateInTheSeriesZoneAndIncludesItsDay() {
		List<ZonedDateTime> days = IcsParser.expand("FREQ=DAILY;UNTIL=20261023", LocalDateTime.of(2026, 10, 20, 23, 30),
				BERLIN, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
		assertThat(days).extracting(ZonedDateTime::toLocalDate).containsExactly(LocalDate.of(2026, 10, 20),
				LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));
	}

	@Test
	@SuppressWarnings("java:S5961")
	void wallClockTimeHoldsAcrossBothChangesOfTheClock() {
		// Sundays at 18:00 in Berlin over 25 October 2026 and 28 March 2027.
		List<ZonedDateTime> sundays = IcsParser.expand("FREQ=WEEKLY", LocalDateTime.of(2026, 10, 18, 18, 0), BERLIN,
				LocalDate.of(2026, 10, 18), LocalDate.of(2027, 4, 4));
		assertThat(sundays).allSatisfy(day -> {
			assertThat(day.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
			assertThat(day.toLocalTime().toString()).isEqualTo("18:00");
		});
		assertThat(at(sundays, LocalDate.of(2026, 10, 18))).isEqualTo(Instant.parse("2026-10-18T16:00:00Z"));
		assertThat(at(sundays, LocalDate.of(2026, 10, 25))).isEqualTo(Instant.parse("2026-10-25T17:00:00Z"));
		assertThat(at(sundays, LocalDate.of(2027, 3, 21))).isEqualTo(Instant.parse("2027-03-21T17:00:00Z"));
		assertThat(at(sundays, LocalDate.of(2027, 3, 28))).isEqualTo(Instant.parse("2027-03-28T16:00:00Z"));
	}

	@Test
	void aTimeInTheGapIsReadWithTheOffsetFromBeforeIt() {
		// 02:30 on 29 March 2026 does not exist in Berlin.
		List<ZonedDateTime> days = IcsParser.expand("FREQ=DAILY;COUNT=2", LocalDateTime.of(2026, 3, 29, 2, 30), BERLIN,
				LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 30));
		assertThat(days.get(0).toInstant()).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
		assertThat(days.get(1).toInstant()).isEqualTo(Instant.parse("2026-03-30T00:30:00Z"));
		assertThat(IcsParser.instant(LocalDateTime.of(2026, 10, 25, 2, 30), BERLIN))
				.as("the hour that happens twice is its first occurrence")
				.isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
	}

	@Test
	void everythingOutsideTheSubsetIsRefused() {
		LocalDateTime first = LocalDateTime.of(2026, 10, 19, 18, 0);
		for (String rule : List.of("FREQ=YEARLY", "FREQ=HOURLY", "FREQ=WEEKLY;BYMONTHDAY=3", "FREQ=DAILY;BYDAY=MO",
				"FREQ=MONTHLY;BYDAY=2MO", "FREQ=WEEKLY;BYDAY=1MO", "FREQ=WEEKLY;INTERVAL=0", "FREQ=WEEKLY;INTERVAL=101",
				"FREQ=WEEKLY;UNTIL=20261231T000000Z", "FREQ=WEEKLY;COUNT=3;UNTIL=20261231", "FREQ=WEEKLY;BYSETPOS=1",
				"FREQ=MONTHLY;BYMONTHDAY=-1", "FREQ=MONTHLY;BYMONTHDAY=1,15", "FREQ=WEEKLY;COUNT=5001", "", "FREQ",
				"FREQ=WEEKLY;FREQ=DAILY", "RRULE:FREQ=WEEKLY")) {
			assertThatThrownBy(() -> IcsParser.expand(rule, first, BERLIN, first.toLocalDate(), first.toLocalDate()))
					.as(rule)
					.isInstanceOfSatisfying(IcsParser.RecurrenceRefused.class,
							refused -> assertThat(refused.reason()).isEqualTo(IcsParser.RecurrenceRefused.Reason.UNSUPPORTED));
		}
	}

	@Test
	void aSpentBudgetIsTooComplex() {
		assertThatThrownBy(() -> IcsParser.expand("FREQ=DAILY", LocalDateTime.of(2026, 1, 1, 9, 0), BERLIN,
				LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 50, Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IcsParser.RecurrenceRefused.class,
						refused -> assertThat(refused.reason()).isEqualTo(IcsParser.RecurrenceRefused.Reason.TOO_COMPLEX));
	}

	private static Instant at(List<ZonedDateTime> days, LocalDate date) {
		return days.stream().filter(day -> day.toLocalDate().equals(date)).findFirst().orElseThrow().toInstant();
	}
}
