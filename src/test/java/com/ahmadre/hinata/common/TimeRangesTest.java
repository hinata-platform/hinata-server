package com.ahmadre.hinata.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overlap rule, stated once because three features will read it: a warning
 * on a time entry, a refusal on a shift, and a subtraction on a working day.
 * The interesting cases are all at the boundary.
 */
class TimeRangesTest {

	private static Instant at(int hour) {
		return Instant.parse("2026-09-07T00:00:00Z").plusSeconds(hour * 3600L);
	}

	private static boolean overlap(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
		return TimeRanges.overlaps(aStart, aEnd, bStart, bEnd);
	}

	@Test
	@DisplayName("back to back is not an overlap")
	void touchingEndsDoNotOverlap() {
		// 09:00–10:00 and 10:00–11:00 is what an ordinary morning looks like.
		// Calling that a clash would flag every day anyone ever files.
		assertThat(overlap(at(9), at(10), at(10), at(11))).isFalse();
		assertThat(overlap(at(10), at(11), at(9), at(10))).isFalse();
	}

	@Test
	@DisplayName("a single shared minute is an overlap, from either side")
	void oneSharedMinuteCounts() {
		assertThat(overlap(at(9), at(11), at(10), at(12))).isTrue();
		assertThat(overlap(at(10), at(12), at(9), at(11))).isTrue();
	}

	@Test
	@DisplayName("containment counts, and so does being contained")
	void nestedSpansOverlap() {
		assertThat(overlap(at(9), at(17), at(12), at(13))).isTrue();
		assertThat(overlap(at(12), at(13), at(9), at(17))).isTrue();
	}

	@Test
	@DisplayName("spans that never meet do not overlap")
	void disjointSpansDoNot() {
		assertThat(overlap(at(9), at(10), at(14), at(15))).isFalse();
		assertThat(overlap(at(14), at(15), at(9), at(10))).isFalse();
	}

	@Test
	@DisplayName("an open end reaches forward forever — a running timer")
	void anAbsentEndIsOpen() {
		// Started at 09:00 and still going: it overlaps anything from 09:00 on,
		// and nothing that finished before it began.
		assertThat(overlap(at(9), null, at(14), at(15))).isTrue();
		assertThat(overlap(at(9), null, at(7), at(8))).isFalse();
		// Touching still does not count, even against an open end.
		assertThat(overlap(at(9), null, at(7), at(9))).isFalse();
	}

	@Test
	@DisplayName("an open start reaches back forever")
	void anAbsentStartIsOpen() {
		assertThat(overlap(null, at(10), at(7), at(8))).isTrue();
		assertThat(overlap(null, at(10), at(11), at(12))).isFalse();
	}

	@Test
	@DisplayName("a span with no bounds at all is always")
	void unboundedOverlapsEverything() {
		assertThat(overlap(null, null, at(9), at(10))).isTrue();
		assertThat(overlap(at(9), at(10), null, null)).isTrue();
		assertThat(overlap(null, null, null, null)).isTrue();
	}

	@Test
	@DisplayName("an empty span occupies nothing")
	void aZeroLengthSpanOverlapsNothing() {
		// Half-open means [10:00, 10:00) is empty. It cannot clash with the span
		// it sits inside, which is the only answer consistent with the rule above.
		assertThat(overlap(at(10), at(10), at(9), at(11))).isFalse();
		assertThat(overlap(at(9), at(11), at(10), at(10))).isFalse();
	}
}
