package com.ahmadre.hinata.timeoff;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The overlap arithmetic every clash warning shares (HIN-118). */
class TimeOffConflictsTest {

	private static final LocalDate MON = LocalDate.of(2026, 6, 15);

	private static TimeOffConflicts.Span span(String who, int from, int to, boolean settled) {
		return new TimeOffConflicts.Span(who, MON.plusDays(from), MON.plusDays(to), settled);
	}

	@Test
	void aSpanEndingOnTheFirstDayOfAnotherClashesWithIt() {
		List<TimeOffConflicts.Span> others = List.of(span("ann", 0, 2, true), span("bob", 3, 4, false),
				span("cid", 5, 6, true));

		assertThat(TimeOffConflicts.clashesWith(others, "me", MON.plusDays(2), MON.plusDays(3))).isEqualTo(2);
		assertThat(TimeOffConflicts.clashesWith(others, "me", MON.plusDays(7), MON.plusDays(9))).isZero();
	}

	@Test
	void theOwnSpansNeverCountAsAClash() {
		List<TimeOffConflicts.Span> others = List.of(span("me", 0, 4, true), span("ann", 1, 1, true));

		assertThat(TimeOffConflicts.clashesWith(others, "me", MON, MON.plusDays(4))).isEqualTo(1);
	}

	@Test
	void perDayCountsPeopleOnceAndASettledDayBeatsARequestedOne() {
		List<TimeOffConflicts.Span> spans = List.of(
				span("ann", 0, 1, true),
				span("ann", 1, 2, true),
				span("bob", 1, 1, false),
				span("bob", 1, 1, true),
				span("cid", -3, 0, false));

		List<TimeOffConflicts.Day> days = TimeOffConflicts.perDay(spans, MON, MON.plusDays(3));

		assertThat(days).extracting(TimeOffConflicts.Day::away).containsExactly(1, 2, 1, 0);
		assertThat(days).extracting(TimeOffConflicts.Day::requested).containsExactly(1, 0, 0, 0);
		assertThat(days.getFirst().date()).isEqualTo(MON);
	}
}
