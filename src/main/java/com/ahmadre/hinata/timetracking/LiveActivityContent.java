package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.notification.SealedLiveActivityState;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The state of a Live Activity as it travels to the iPhone, sealed (HIN-103).
 *
 * <p>The plaintext is the timer's shape and nothing else: mode, phase, the
 * completed cycles, the start, the end of the stretch and whether it ended.
 * No description, project, issue, tag or id ever goes in — the words on the
 * lock screen come from the app, in the reader's language, kept on the phone.
 *
 * <p>It is sealed with AES-256-GCM under the key the app made for this one
 * activity, with a fresh 96-bit nonce and the activity reference as associated
 * data, so a state cannot be replayed into another activity. The wire form is
 * {@code {"v":1,"n":<nonce>,"c":<ciphertext+tag>}}, both standard Base64. The
 * gateway, Google and Apple see that and the moment it was sent; the widget
 * extension opens it with CryptoKit ({@code AES.GCM.SealedBox}).
 */
public final class LiveActivityContent {

	/** The version of the sealed form; the extension refuses any other. */
	public static final int VERSION = 1;

	private static final String TRANSFORM = "AES/GCM/NoPadding";
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** How long after the end of its stretch a local timer surface stays (the app's rule). */
	private static final Duration AFTER_END = Duration.ofHours(1);
	/** How long a local timer surface may live at most (the app's rule). */
	private static final Duration LIFETIME = Duration.ofHours(24);

	private LiveActivityContent() {
	}

	/**
	 * What the activity shows for [timer]; null means the timer ended.
	 *
	 * @param endsAt   the end of a countdown or a phase, null for a stopwatch
	 * @param dismissAt when the activity goes away by itself at the latest
	 */
	public record State(String mode, String phase, int cyclesDone, Instant startedAt,
			Instant endsAt, Instant dismissAt, boolean ended) {

		/** The plaintext before sealing. Every value is a name or a number; no free text. */
		public String json() {
			StringBuilder s = new StringBuilder("{\"mode\":\"").append(mode).append('"');
			s.append(",\"phase\":").append(phase == null ? "null" : "\"" + phase + "\"");
			s.append(",\"cyclesDone\":").append(cyclesDone);
			s.append(",\"startedAt\":").append(startedAt == null ? "null" : startedAt.toEpochMilli());
			s.append(",\"endsAt\":").append(endsAt == null ? "null" : endsAt.toEpochMilli());
			s.append(",\"ended\":").append(ended);
			return s.append('}').toString();
		}
	}

	/** The state of a running [timer]. */
	public static State of(RunningTimer timer) {
		Instant startedAt = timer.getStartedAt();
		Instant endsAt = endOf(timer);
		Instant byDay = startedAt.plus(LIFETIME);
		Instant dismissAt = endsAt == null || endsAt.plus(AFTER_END).isAfter(byDay)
				? byDay : endsAt.plus(AFTER_END);
		return new State(mode(timer.getMode()), phase(timer.getPhase()), timer.getCyclesDone(),
				startedAt, endsAt, dismissAt, false);
	}

	/** The state that ends an activity: no times, only the fact. */
	public static State ended(Instant now) {
		return new State("stopwatch", null, 0, null, null, now, true);
	}

	/** Seals [state] for the activity [activityId] under [key]. */
	public static SealedLiveActivityState seal(State state, byte[] key, String activityId) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			RANDOM.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance(TRANSFORM);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData(activityId));
			byte[] sealed = cipher.doFinal(state.json().getBytes(StandardCharsets.UTF_8));
			Base64.Encoder b64 = Base64.getEncoder();
			return new SealedLiveActivityState(VERSION, b64.encodeToString(nonce), b64.encodeToString(sealed));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("live activity state could not be sealed", ex);
		}
	}

	/** Opens a [sealed] state; for tests and nothing else on the server. */
	static String open(SealedLiveActivityState sealed, byte[] key, String activityId) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(TRANSFORM);
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(sealed.n())));
		cipher.updateAAD(associatedData(activityId));
		return new String(cipher.doFinal(Base64.getDecoder().decode(sealed.c())), StandardCharsets.UTF_8);
	}

	/** Shared with the extension byte for byte: {@code "hinata-la-v1:" + activityId}. */
	static byte[] associatedData(String activityId) {
		return ("hinata-la-v1:" + activityId).getBytes(StandardCharsets.UTF_8);
	}

	private static Instant endOf(RunningTimer timer) {
		RunningTimer.Mode mode = timer.getMode();
		if (mode == RunningTimer.Mode.COUNTDOWN && timer.getPlannedMinutes() != null) {
			return timer.getStartedAt().plus(Duration.ofMinutes(timer.getPlannedMinutes()));
		}
		RunningTimer.Pomodoro config = timer.getPomodoro();
		if (mode == RunningTimer.Mode.POMODORO && config != null && timer.getPhase() != null) {
			int minutes = switch (timer.getPhase()) {
				case WORK -> config.getWork();
				case BREAK -> config.getShortBreak();
				case LONG_BREAK -> config.getLongBreak();
			};
			return timer.getStartedAt().plus(Duration.ofMinutes(minutes));
		}
		return null;
	}

	/** The app's names, so the extension needs no table of the server's. */
	private static String mode(RunningTimer.Mode mode) {
		return switch (mode == null ? RunningTimer.Mode.STOPWATCH : mode) {
			case STOPWATCH -> "stopwatch";
			case COUNTDOWN -> "countdown";
			case POMODORO -> "pomodoro";
		};
	}

	private static String phase(RunningTimer.Phase phase) {
		if (phase == null) return null;
		return switch (phase) {
			case WORK -> "work";
			case BREAK -> "shortBreak";
			case LONG_BREAK -> "longBreak";
		};
	}
}
