package com.ahmadre.hinata.me;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How this person likes their timer to count: pomodoro lengths, the countdown
 * they usually reach for, and whether the end of an interval makes a sound.
 * Embedded in the {@code users} document.
 *
 * <p>Here rather than in {@code ServerSettings} because these are the person's
 * own working rhythm, and an administrator prescribing how long somebody's
 * breaks are is precisely the kind of thing the module is built not to do
 * (HIN-60 R2/R7). Nothing reads them but the account that owns them.
 *
 * <p>Here rather than in the {@code timetracking} package because {@code User}
 * embeds them and the module boundary runs the other way — {@code ModuleBoundaryTest}
 * keeps the rest of the product from depending on a module that can be switched
 * off. A few numbers nobody reads while the module is off cost nothing; an
 * import from {@code user} into {@code timetracking} would cost the boundary.
 *
 * <p>Every value is clamped rather than rejected on the way in. These arrive on
 * {@code PATCH /me}, and a preference is not worth failing a profile save over:
 * the request validators state the same bounds, so a client sending 0 is a bug
 * on its side, and the honest repair is the nearest usable number rather than a
 * 400 that leaves the person unable to change their display name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimePreferences {

	/** Minutes of one pomodoro work interval. */
	public static final int MIN_WORK = 1;
	public static final int MAX_WORK = 180;

	/** Minutes of a short break — the one taken after every work interval but the last of a set. */
	public static final int MIN_BREAK = 1;
	public static final int MAX_BREAK = 60;

	/** Minutes of the long break, taken once a set of cycles is complete. */
	public static final int MIN_LONG_BREAK = 1;
	public static final int MAX_LONG_BREAK = 180;

	/** Work intervals per set. Two is the smallest number for which a long break means anything. */
	public static final int MIN_CYCLES = 2;
	public static final int MAX_CYCLES = 12;

	/** Minutes a countdown runs. Bounded by the timer ceiling, not by taste. */
	public static final int MIN_COUNTDOWN = 1;
	public static final int MAX_COUNTDOWN = 24 * 60;

	private int pomodoroWork = 25;
	private int pomodoroShortBreak = 5;
	private int pomodoroLongBreak = 15;
	private int pomodoroCycles = 4;

	/** What the countdown offers first — the last length that was chosen, in effect. */
	private int countdownMinutes = 25;

	/** Whether the end of an interval plays a sound. The toast appears either way. */
	private boolean sound = true;

	public static TimePreferences defaults() {
		return new TimePreferences();
	}

	/**
	 * A copy with every value inside its bounds.
	 *
	 * <p>Applied on the way in and on the way out: a document written before a
	 * bound existed, or by a client that ignored one, must not be able to start a
	 * pomodoro that never breaks or a set that never completes.
	 */
	public TimePreferences sanitized() {
		return new TimePreferences(
				clamp(pomodoroWork, MIN_WORK, MAX_WORK, 25),
				clamp(pomodoroShortBreak, MIN_BREAK, MAX_BREAK, 5),
				clamp(pomodoroLongBreak, MIN_LONG_BREAK, MAX_LONG_BREAK, 15),
				clamp(pomodoroCycles, MIN_CYCLES, MAX_CYCLES, 4),
				clamp(countdownMinutes, MIN_COUNTDOWN, MAX_COUNTDOWN, 25),
				sound);
	}

	/**
	 * {@code value} inside its bounds, or {@code fallback} when it is zero.
	 *
	 * <p>Zero is treated as "never set" rather than clamped up to the minimum:
	 * an {@code int} field on a document written before this class existed reads
	 * back as 0, and the person's preference then has to be the default, not one
	 * minute.
	 */
	private static int clamp(int value, int min, int max, int fallback) {
		if (value == 0) {
			return fallback;
		}
		return Math.clamp(value, min, max);
	}
}
