package com.ahmadre.hinata.timetracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TimerLiveActivityPushBudgetTest {

	@Test
	void aPersonSendsAtMostTheMinutesBudget_countedPerActivity_andAgainNextMinute() {
		TimerLiveActivityPush.Slot slot = new TimerLiveActivityPush.Slot();
		Instant now = Instant.parse("2026-10-12T12:00:00Z");

		for (int i = 0; i < TimerLiveActivityPush.MAX_PUSHES_PER_MINUTE / 20; i++) {
			assertThat(slot.spend(20, now.plusSeconds(2L * i))).isTrue();
		}
		assertThat(slot.spend(20, now.plusSeconds(10))).isFalse();
		assertThat(slot.spend(1, now.plusSeconds(61))).isTrue();
	}
}
