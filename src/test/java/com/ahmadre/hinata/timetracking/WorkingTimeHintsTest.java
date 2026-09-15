package com.ahmadre.hinata.timetracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic of the self-hints, on its own. What matters most is what does
 * <em>not</em> produce a hint: a ten-hour day, eleven hours of rest, an overlap, a
 * day typed as a bare duration, an entry recorded exactly on the limit.
 */
class WorkingTimeHintsTest {

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
	private static final WorkingTimeHints.Rules ACT = new WorkingTimeHints.Rules(true, null);

	private static WorkingTimeHints.Entry typed(String id, LocalDate day, int minutes) {
		return new WorkingTimeHints.Entry(id, day, minutes, null, null, null);
	}

	private static WorkingTimeHints.Entry timed(String id, LocalDate day, String start, String end) {
		Instant from = Instant.parse(start);
		Instant to = Instant.parse(end);
		return new WorkingTimeHints.Entry(id, day, (int) java.time.Duration.between(from, to).toMinutes(),
				from, to, null);
	}

	@Test
	void tenHoursIsTheLimitAndOneMinuteMoreIsAHint() {
		assertThat(WorkingTimeHints.of(List.of(typed("a", MONDAY, 600)), MONDAY, MONDAY, UTC, ACT))
				.isEmpty();

		assertThat(WorkingTimeHints.of(List.of(typed("a", MONDAY, 400), typed("b", MONDAY, 201)),
				MONDAY, MONDAY, UTC, ACT))
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.DAILY_MAXIMUM);
					assertThat(hint.minutes()).isEqualTo(601);
				});
	}

	@Test
	void restIsMeasuredFromTheLastEndOfOneDayToTheFirstStartOfTheNext() {
		LocalDate tuesday = MONDAY.plusDays(1);
		List<WorkingTimeHints.Entry> entries = List.of(
				timed("m1", MONDAY, "2026-09-07T08:00:00Z", "2026-09-07T12:00:00Z"),
				timed("m2", MONDAY, "2026-09-07T18:00:00Z", "2026-09-07T22:00:00Z"),
				timed("t1", tuesday, "2026-09-08T07:00:00Z", "2026-09-08T11:00:00Z"));

		assertThat(WorkingTimeHints.of(entries, tuesday, tuesday, UTC, ACT))
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.SHORT_REST);
					assertThat(hint.date()).isEqualTo(tuesday);
					assertThat(hint.restMinutes()).isEqualTo(9 * 60);
				});
	}

	@Test
	void elevenHoursOfRestIsEnoughAndAnOverlapIsNotCalledRest() {
		LocalDate tuesday = MONDAY.plusDays(1);
		List<WorkingTimeHints.Entry> exactlyEleven = List.of(
				timed("m", MONDAY, "2026-09-07T12:00:00Z", "2026-09-07T20:00:00Z"),
				timed("t", tuesday, "2026-09-08T07:00:00Z", "2026-09-08T10:00:00Z"));
		assertThat(WorkingTimeHints.of(exactlyEleven, tuesday, tuesday, UTC, ACT)).isEmpty();

		List<WorkingTimeHints.Entry> overnight = List.of(
				timed("m", MONDAY, "2026-09-07T20:00:00Z", "2026-09-08T02:00:00Z"),
				timed("t", tuesday, "2026-09-08T01:00:00Z", "2026-09-08T03:00:00Z"));
		assertThat(WorkingTimeHints.of(overnight, tuesday, tuesday, UTC, ACT)).isEmpty();
	}

	@Test
	void aDayTypedAsABareDurationSaysNothingAboutRest() {
		LocalDate tuesday = MONDAY.plusDays(1);
		List<WorkingTimeHints.Entry> entries = List.of(typed("m", MONDAY, 480),
				timed("t", tuesday, "2026-09-08T05:00:00Z", "2026-09-08T09:00:00Z"));

		assertThat(WorkingTimeHints.of(entries, tuesday, tuesday, UTC, ACT)).isEmpty();
	}

	@Test
	void theRestBeforeTheFirstDayOfTheWindowIsJudgedFromTheDayBeforeIt() {
		LocalDate tuesday = MONDAY.plusDays(1);
		List<WorkingTimeHints.Entry> entries = List.of(
				timed("m", MONDAY, "2026-09-07T14:00:00Z", "2026-09-07T23:00:00Z"),
				timed("t", tuesday, "2026-09-08T06:00:00Z", "2026-09-08T08:00:00Z"));

		assertThat(WorkingTimeHints.of(entries, tuesday, tuesday.plusDays(3), UTC, ACT))
				.extracting(WorkingTimeHints.Hint::kind)
				.containsExactly(WorkingTimeHints.Kind.SHORT_REST);
	}

	@Test
	void hoursOnASundayAreNamedAndASundayWithoutHoursIsNot() {
		LocalDate sunday = MONDAY.minusDays(1);
		assertThat(WorkingTimeHints.of(List.of(typed("s", sunday, 30)), sunday, MONDAY, UTC, ACT))
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.SUNDAY_WORK);
					assertThat(hint.date()).isEqualTo(sunday);
				});
		assertThat(WorkingTimeHints.of(List.of(typed("m", MONDAY, 30)), sunday, MONDAY, UTC, ACT))
				.isEmpty();
	}

	@Test
	void hoursOnAHolidayAreNamedAndAHolidayWithoutHoursIsNot() {
		LocalDate holiday = MONDAY.plusDays(2);
		LocalDate idleHoliday = MONDAY.plusDays(3);
		List<WorkingTimeHints.Entry> entries = List.of(typed("h", holiday, 30), typed("t", MONDAY.plusDays(1), 30));

		assertThat(WorkingTimeHints.of(entries, MONDAY, idleHoliday, UTC, ACT, Set.of(holiday, idleHoliday)))
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.HOLIDAY_WORK);
					assertThat(hint.date()).isEqualTo(holiday);
				});
		// With the Working Hours Act hints off, a holiday says nothing either.
		assertThat(WorkingTimeHints.of(entries, MONDAY, idleHoliday, UTC, new WorkingTimeHints.Rules(false, null),
				Set.of(holiday))).isEmpty();
	}

	@Test
	void aLateEntryIsMeasuredFromTheDayItWasRecordedInThePersonsZone() {
		WorkingTimeHints.Rules seven = new WorkingTimeHints.Rules(false, 7);
		// Recorded at 23:30 UTC on the 14th, which is already the 15th in Berlin.
		Instant recorded = Instant.parse("2026-09-14T23:30:00Z");
		WorkingTimeHints.Entry entry = new WorkingTimeHints.Entry("e", MONDAY, 60, null, null, recorded);

		assertThat(WorkingTimeHints.of(List.of(entry), MONDAY, MONDAY, UTC, seven))
				.as("exactly seven days late is on the limit, not over it")
				.isEmpty();
		assertThat(WorkingTimeHints.of(List.of(entry), MONDAY, MONDAY, ZoneId.of("Europe/Berlin"), seven))
				.singleElement()
				.satisfies(hint -> {
					assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.LATE_ENTRY);
					assertThat(hint.entryId()).isEqualTo("e");
					assertThat(hint.daysLate()).isEqualTo(8);
				});
	}

	@Test
	void anEntryWithoutARecordingTimeIsNeverLateAndSwitchedOffHintsSayNothing() {
		WorkingTimeHints.Entry legacy = typed("old", MONDAY.minusYears(1), 900);

		assertThat(WorkingTimeHints.of(List.of(legacy), MONDAY.minusYears(1), MONDAY.minusYears(1), UTC,
				new WorkingTimeHints.Rules(false, 1))).isEmpty();
		assertThat(WorkingTimeHints.of(List.of(legacy), MONDAY.minusYears(1), MONDAY.minusYears(1), UTC,
				new WorkingTimeHints.Rules(false, null))).isEmpty();
	}
}
