package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.TimePolicy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;

/**
 * How this person likes their timer to count: pomodoro lengths, the countdown
 * they usually reach for, and whether the end of an interval makes a sound.
 * Since HIN-92 also the targets they set themselves and when they want to be
 * reminded of them. Embedded in the {@code users} document.
 *
 * <p>Here rather than in {@code ServerSettings} because these are the person's
 * own working rhythm, and an administrator prescribing how long somebody's
 * breaks are is precisely the kind of thing the module is built not to do
 * (HIN-60 R2/R7). Nothing reads them but the account that owns them — and the
 * reminder job, which only ever writes back to that same account.
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
 *
 * <p>The reminder fields are object types with their defaults in the getters: a
 * primitive added to a document that predates it reads back as 0, and 0 is a
 * target and a time of day (midnight) rather than "never set".
 */
@Data
@NoArgsConstructor
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

	/** A reminder time is a minute of the day, 0 to 1439. */
	public static final int LAST_MINUTE_OF_DAY = 24 * 60 - 1;

	/** 17:00 for the day, Friday 16:00 for the week (HIN-92). */
	public static final int DEFAULT_DAILY_REMINDER_AT = 17 * 60;
	public static final int DEFAULT_WEEKLY_REMINDER_AT = 16 * 60;
	public static final DayOfWeek DEFAULT_WEEKLY_REMINDER_DAY = DayOfWeek.FRIDAY;

	private int pomodoroWork = 25;
	private int pomodoroShortBreak = 5;
	private int pomodoroLongBreak = 15;
	private int pomodoroCycles = 4;

	/** What the countdown offers first — the last length that was chosen, in effect. */
	private int countdownMinutes = 25;

	/** Whether the end of an interval plays a sound. The toast appears either way. */
	private boolean sound = true;

	/** The minutes this person wants to record on a working day; null for no daily reminder. */
	private Integer dailyTargetMinutes;

	/** The minutes this person wants to record in a week; null for no weekly reminder. */
	private Integer weeklyTargetMinutes;

	/** The minute of the day, in the person's own zone, the daily reminder is due. */
	private Integer dailyReminderAt;

	/** The minute of the day, in the person's own zone, the weekly reminder is due. */
	private Integer weeklyReminderAt;

	/** The day of the week the weekly reminder is due. */
	private DayOfWeek weeklyReminderDay;

	/**
	 * Whether a target is set, written by {@link #sanitized()}. Stored only so the
	 * reminder job finds the few people with a target through a partial index,
	 * instead of reading every account four times an hour. Never sent to a client:
	 * it is the targets themselves that say it.
	 */
	@JsonIgnore
	private Boolean targetsSet;

	public static TimePreferences defaults() {
		return new TimePreferences();
	}

	public int getDailyReminderAt() {
		return dailyReminderAt == null ? DEFAULT_DAILY_REMINDER_AT : dailyReminderAt;
	}

	public int getWeeklyReminderAt() {
		return weeklyReminderAt == null ? DEFAULT_WEEKLY_REMINDER_AT : weeklyReminderAt;
	}

	public DayOfWeek getWeeklyReminderDay() {
		return weeklyReminderDay == null ? DEFAULT_WEEKLY_REMINDER_DAY : weeklyReminderDay;
	}

	@JsonIgnore
	public boolean isTargetsSet() {
		return Boolean.TRUE.equals(targetsSet);
	}

	/**
	 * A copy with every value inside its bounds.
	 *
	 * <p>Applied on the way in and on the way out: a document written before a
	 * bound existed, or by a client that ignored one, must not be able to start a
	 * pomodoro that never breaks or a set that never completes.
	 */
	public TimePreferences sanitized() {
		TimePreferences copy = new TimePreferences();
		copy.pomodoroWork = clamp(pomodoroWork, MIN_WORK, MAX_WORK, 25);
		copy.pomodoroShortBreak = clamp(pomodoroShortBreak, MIN_BREAK, MAX_BREAK, 5);
		copy.pomodoroLongBreak = clamp(pomodoroLongBreak, MIN_LONG_BREAK, MAX_LONG_BREAK, 15);
		copy.pomodoroCycles = clamp(pomodoroCycles, MIN_CYCLES, MAX_CYCLES, 4);
		copy.countdownMinutes = clamp(countdownMinutes, MIN_COUNTDOWN, MAX_COUNTDOWN, 25);
		copy.sound = sound;
		copy.dailyTargetMinutes = target(dailyTargetMinutes, TimePolicy.MAX_DAILY_TARGET_MINUTES);
		copy.weeklyTargetMinutes = target(weeklyTargetMinutes, TimePolicy.MAX_WEEKLY_TARGET_MINUTES);
		copy.dailyReminderAt = minuteOfDay(dailyReminderAt);
		copy.weeklyReminderAt = minuteOfDay(weeklyReminderAt);
		copy.weeklyReminderDay = weeklyReminderDay;
		copy.targetsSet = copy.dailyTargetMinutes != null || copy.weeklyTargetMinutes != null;
		return copy;
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

	/** A target up to {@code max}; zero or less is no target, which is how a client removes one. */
	private static Integer target(Integer minutes, int max) {
		return minutes == null || minutes <= 0 ? null : Math.min(minutes, max);
	}

	private static Integer minuteOfDay(Integer minute) {
		return minute == null ? null : Math.clamp(minute, 0, LAST_MINUTE_OF_DAY);
	}
}
