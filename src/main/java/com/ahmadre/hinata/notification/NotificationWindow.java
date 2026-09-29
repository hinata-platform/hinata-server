package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.me.NotificationPreferences;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * When e-mail and push may reach a person (HIN-131): always, or on some days of the week
 * between two times of day, in the person's own zone.
 *
 * <p>A window whose end lies before its start runs over midnight and belongs to the day it
 * starts on: 22:00 to 06:00 on Fridays is open from Friday night to Saturday morning.
 *
 * @param always whether there is no schedule at all
 * @param days   the days a window opens on
 * @param from   the window's start, or null for the whole day
 * @param until  the window's end, or null for the whole day
 */
public record NotificationWindow(boolean always, Set<DayOfWeek> days, LocalTime from, LocalTime until) {

	/** No schedule: e-mail and push go out as they happen. */
	public static final NotificationWindow ALWAYS = new NotificationWindow(true, Set.of(), null, null);

	/** The office hours an organisation that works in working days starts everybody on. */
	public static final LocalTime DEFAULT_FROM = LocalTime.of(9, 0);
	public static final LocalTime DEFAULT_UNTIL = LocalTime.of(17, 0);

	public NotificationWindow {
		days = days == null || days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
		if (from == null || until == null || from.equals(until)) {
			from = null;
			until = null;
		}
	}

	/**
	 * The window a person's stored preferences work out to.
	 *
	 * @param prefs    the person's sanitized preferences
	 * @param workdays the working days where the person lives
	 * @param orgBasis how the organisation counts deadlines; working days make office hours
	 *                 the default for everybody who has not chosen
	 */
	public static NotificationWindow of(NotificationPreferences prefs, Set<DayOfWeek> workdays,
			RelativeDate.Basis orgBasis) {
		NotificationPreferences.Schedule schedule = prefs.getSchedule();
		if (schedule == NotificationPreferences.Schedule.ALWAYS) return ALWAYS;
		Set<DayOfWeek> days = prefs.getWeekdays() != null && !prefs.getWeekdays().isEmpty()
				? EnumSet.copyOf(prefs.getWeekdays()) : workdays;
		if (schedule == NotificationPreferences.Schedule.CUSTOM) {
			return new NotificationWindow(false, days, NotificationPreferences.time(prefs.getFrom()),
					NotificationPreferences.time(prefs.getUntil()));
		}
		// Days picked before there were schedules: those days, all day long.
		if (prefs.getWeekdays() != null && !prefs.getWeekdays().isEmpty()) {
			return new NotificationWindow(false, days, null, null);
		}
		return defaultFor(workdays, orgBasis);
	}

	/** What applies while a person has not chosen. */
	public static NotificationWindow defaultFor(Set<DayOfWeek> workdays, RelativeDate.Basis orgBasis) {
		return orgBasis == RelativeDate.Basis.WORKING
				? new NotificationWindow(false, workdays, DEFAULT_FROM, DEFAULT_UNTIL)
				: ALWAYS;
	}

	/** Whether e-mail and push may go out at {@code local}, a time in the person's zone. */
	public boolean isOpen(LocalDateTime local) {
		if (always) return true;
		DayOfWeek day = local.getDayOfWeek();
		if (from == null) return days.contains(day);
		LocalTime time = local.toLocalTime();
		if (from.isBefore(until)) {
			return days.contains(day) && !time.isBefore(from) && time.isBefore(until);
		}
		// Over midnight: the evening half belongs to today, the morning half to yesterday.
		if (!time.isBefore(from)) return days.contains(day);
		return time.isBefore(until) && days.contains(day.minus(1));
	}
}
