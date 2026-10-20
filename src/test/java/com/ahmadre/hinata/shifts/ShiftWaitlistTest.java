package com.ahmadre.hinata.shifts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The waiting list's arithmetic (HIN-162), without Spring. */
class ShiftWaitlistTest {

	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

	@Test
	@DisplayName("a shift far ahead gives a day to confirm")
	void aDayAtMost() {
		assertThat(ShiftWaitlist.confirmBy(NOW, NOW.plus(Duration.ofDays(10)))).isEqualTo(NOW.plus(Duration.ofHours(24)));
	}

	@Test
	@DisplayName("a shift tomorrow morning is confirmed two hours before it starts")
	void twoHoursBeforeTheStart() {
		Instant start = NOW.plus(Duration.ofHours(12));
		assertThat(ShiftWaitlist.confirmBy(NOW, start)).isEqualTo(start.minus(Duration.ofHours(2)));
	}

	@Test
	@DisplayName("never less than half an hour, even right at the cutoff")
	void halfAnHourAtLeast() {
		assertThat(ShiftWaitlist.confirmBy(NOW, NOW.plus(Duration.ofMinutes(130)))).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
		assertThat(ShiftWaitlist.confirmBy(NOW, NOW.plus(Duration.ofHours(2)))).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
	}

	@Test
	@DisplayName("the hourly run's planned time is the last 35 minutes past the hour that has come")
	void plannedTime() {
		assertThat(ShiftWaitlistJob.planned(Instant.parse("2026-10-20T10:35:02Z"))).isEqualTo(Instant.parse("2026-10-20T10:35:00Z"));
		assertThat(ShiftWaitlistJob.planned(Instant.parse("2026-10-20T11:10:00Z"))).isEqualTo(Instant.parse("2026-10-20T10:35:00Z"));
	}

	@Test
	@DisplayName("only a higher limit or none at all opens places")
	void raises() {
		assertThat(ShiftService.raises(3, 4)).isTrue();
		assertThat(ShiftService.raises(3, null)).isTrue();
		assertThat(ShiftService.raises(3, 3)).isFalse();
		assertThat(ShiftService.raises(3, 2)).isFalse();
		assertThat(ShiftService.raises(null, 5)).isFalse();
	}
}
