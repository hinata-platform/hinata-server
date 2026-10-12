package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.notification.SealedLiveActivityState;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state of a Live Activity as it leaves the server (HIN-103): the shape of
 * the timer and nothing of its content, sealed so that only the activity's key
 * opens it.
 */
class LiveActivityContentTest {

	private static final Instant START = Instant.parse("2026-10-12T08:00:00Z");

	private static byte[] key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private static RunningTimer described(RunningTimer.RunningTimerBuilder builder) {
		return builder.id("timer-4711").userId("user-1").startedAt(START)
				.projectId("project-secret").issueId("issue-secret")
				.description("Gehaltsverhandlung Müller").tags(List.of("tag-secret"))
				.activityType("activity-secret").billable(true).build();
	}

	@Test
	void thePlaintextCarriesTheShapeOfTheTimerAndNothingOfItsContent() {
		RunningTimer timer = described(RunningTimer.builder().mode(RunningTimer.Mode.POMODORO)
				.pomodoro(new RunningTimer.Pomodoro(25, 5, 15, 4)).phase(RunningTimer.Phase.WORK)
				.phaseStartedAt(START).cyclesDone(2));

		String json = LiveActivityContent.of(timer).json();

		assertThat(json).doesNotContain("Gehalt", "Müller", "project", "issue", "tag", "timer-4711",
				"user-1", "activity-secret", "billable", "description");
		assertThat(json).isEqualTo("{\"mode\":\"pomodoro\",\"phase\":\"work\",\"cyclesDone\":2,\"startedAt\":"
				+ START.toEpochMilli() + ",\"endsAt\":" + START.plusSeconds(25 * 60).toEpochMilli()
				+ ",\"ended\":false}");
	}

	@Test
	void theEndOfAStretchFollowsTheModeAndThePhase() {
		RunningTimer countdown = described(RunningTimer.builder().mode(RunningTimer.Mode.COUNTDOWN)
				.plannedMinutes(45));
		RunningTimer longBreak = described(RunningTimer.builder().mode(RunningTimer.Mode.POMODORO)
				.pomodoro(new RunningTimer.Pomodoro(25, 5, 15, 4)).phase(RunningTimer.Phase.LONG_BREAK));
		RunningTimer stopwatch = described(RunningTimer.builder().mode(RunningTimer.Mode.STOPWATCH));

		assertThat(LiveActivityContent.of(countdown).endsAt()).isEqualTo(START.plusSeconds(45 * 60));
		assertThat(LiveActivityContent.of(longBreak).endsAt()).isEqualTo(START.plusSeconds(15 * 60));
		assertThat(LiveActivityContent.of(longBreak).phase()).isEqualTo("longBreak");
		assertThat(LiveActivityContent.of(stopwatch).endsAt()).isNull();
		// A stopwatch goes away a day after its start, a countdown an hour after its end.
		assertThat(LiveActivityContent.of(stopwatch).dismissAt()).isEqualTo(START.plusSeconds(24 * 3600));
		assertThat(LiveActivityContent.of(countdown).dismissAt()).isEqualTo(START.plusSeconds(105 * 60));
	}

	@Test
	void theSealedStateOpensWithTheKeyOfItsActivityAndWithNothingElse() throws Exception {
		byte[] key = key();
		LiveActivityContent.State state = LiveActivityContent.of(described(RunningTimer.builder()
				.mode(RunningTimer.Mode.COUNTDOWN).plannedMinutes(10)));

		SealedLiveActivityState sealed = LiveActivityContent.seal(state, key, "activity-a");

		assertThat(sealed.v()).isEqualTo(1);
		assertThat(Base64.getDecoder().decode(sealed.n())).hasSize(12);
		assertThat(sealed.c()).doesNotContain("countdown");
		assertThat(LiveActivityContent.open(sealed, key, "activity-a")).isEqualTo(state.json());
		assertThatThrownBy(() -> LiveActivityContent.open(sealed, key(), "activity-a"))
				.isInstanceOf(AEADBadTagException.class);
		// Replayed into another activity of the same person, it does not open.
		assertThatThrownBy(() -> LiveActivityContent.open(sealed, key, "activity-b"))
				.isInstanceOf(AEADBadTagException.class);
	}

	@Test
	void everySealUsesAFreshNonce() {
		byte[] key = key();
		LiveActivityContent.State state = LiveActivityContent.ended(START);

		assertThat(LiveActivityContent.seal(state, key, "a").n())
				.isNotEqualTo(LiveActivityContent.seal(state, key, "a").n());
	}

	/**
	 * A vector the widget extension's test opens with CryptoKit
	 * ({@code TimerActivityCryptoTests.swift}); the two sides agree on the
	 * associated data and the layout of nonce, ciphertext and tag.
	 */
	@Test
	void aFixedVectorOpensToTheSamePlaintextTheExtensionExpects() throws Exception {
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) key[i] = (byte) i;
		SealedLiveActivityState vector = new SealedLiveActivityState(1, "AAECAwQFBgcICQoL",
				"PCC7dKGA4CGvIvj+350cAvS4pRjSCzcdSwLHv3McbN4tMs2FzK136zDLEYiqvRgUzCoU7Ciixr5+4wgjKdTM38cF9Erj4RZRLHiIC4HrfalMqPNTQVpiX8TPqPxIwIm959o8iMAkrDikel/eOxBcce5lDgxvbFZM1VoVi7P9Kg==");

		assertThat(LiveActivityContent.open(vector, key, "vector-activity")).isEqualTo("{\"mode\":\"countdown\",\"phase\":null,"
				+ "\"cyclesDone\":0,\"startedAt\":1791792000000,\"endsAt\":1791792600000,\"ended\":false}");
	}
}
