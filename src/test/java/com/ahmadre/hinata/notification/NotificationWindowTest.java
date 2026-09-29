package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.me.NotificationPreferences;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationWindowTest {

	private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(MONDAY, FRIDAY);

	/** 2026-09-28 is a Monday. */
	private static LocalDateTime monday(int hour, int minute) {
		return LocalDateTime.of(2026, 9, 28, hour, minute);
	}

	private static LocalDateTime saturday(int hour, int minute) {
		return LocalDateTime.of(2026, 10, 3, hour, minute);
	}

	private static NotificationPreferences prefs() {
		return NotificationPreferences.defaults();
	}

	@Test
	void withoutAChoiceCalendarDaysMeanAlways() {
		NotificationWindow window = NotificationWindow.of(prefs().sanitized(), WEEKDAYS, RelativeDate.Basis.CALENDAR);
		assertThat(window.always()).isTrue();
		assertThat(window.isOpen(saturday(3, 0))).isTrue();
	}

	@Test
	void withoutAChoiceWorkingDaysMeanOfficeHoursOnWorkingDays() {
		NotificationWindow window = NotificationWindow.of(prefs().sanitized(), WEEKDAYS, RelativeDate.Basis.WORKING);
		assertThat(window.isOpen(monday(9, 0))).isTrue();
		assertThat(window.isOpen(monday(16, 59))).isTrue();
		assertThat(window.isOpen(monday(17, 0))).isFalse();
		assertThat(window.isOpen(monday(8, 59))).isFalse();
		assertThat(window.isOpen(saturday(12, 0))).isFalse();
	}

	@Test
	void daysPickedBeforeSchedulesStayAllDay() {
		NotificationPreferences picked = prefs();
		picked.setWeekdays(List.of(SATURDAY, SUNDAY));
		NotificationWindow window = NotificationWindow.of(picked.sanitized(), WEEKDAYS, RelativeDate.Basis.WORKING);
		assertThat(window.isOpen(saturday(23, 30))).isTrue();
		assertThat(window.isOpen(monday(12, 0))).isFalse();
	}

	@Test
	void alwaysWinsOverTheOrganisation() {
		NotificationPreferences always = prefs();
		always.setSchedule(NotificationPreferences.Schedule.ALWAYS);
		NotificationWindow window = NotificationWindow.of(always.sanitized(), WEEKDAYS, RelativeDate.Basis.WORKING);
		assertThat(window.isOpen(saturday(3, 0))).isTrue();
	}

	@Test
	void aCustomWindowUsesItsOwnDaysAndHours() {
		NotificationPreferences custom = prefs();
		custom.setSchedule(NotificationPreferences.Schedule.CUSTOM);
		custom.setWeekdays(List.of(SATURDAY));
		custom.setFrom("10:00");
		custom.setUntil("12:30");
		NotificationWindow window = NotificationWindow.of(custom.sanitized(), WEEKDAYS, RelativeDate.Basis.CALENDAR);
		assertThat(window.isOpen(saturday(11, 0))).isTrue();
		assertThat(window.isOpen(saturday(12, 30))).isFalse();
		assertThat(window.isOpen(monday(11, 0))).isFalse();
	}

	@Test
	void aWindowOverMidnightBelongsToTheDayItStarts() {
		NotificationPreferences night = prefs();
		night.setSchedule(NotificationPreferences.Schedule.CUSTOM);
		night.setWeekdays(List.of(FRIDAY));
		night.setFrom("22:00");
		night.setUntil("06:00");
		NotificationWindow window = NotificationWindow.of(night.sanitized(), WEEKDAYS, RelativeDate.Basis.CALENDAR);
		LocalDateTime fridayNight = LocalDateTime.of(2026, 10, 2, 23, 0);
		LocalDateTime saturdayMorning = LocalDateTime.of(2026, 10, 3, 5, 0);
		LocalDateTime mondayMorning = monday(5, 0);
		assertThat(window.isOpen(fridayNight)).isTrue();
		assertThat(window.isOpen(saturdayMorning)).isTrue();
		assertThat(window.isOpen(mondayMorning)).isFalse();
		assertThat(window.isOpen(saturday(7, 0))).isFalse();
	}

	@Test
	void aCustomScheduleWithoutDaysFallsBackToTheWorkingDays() {
		NotificationPreferences custom = prefs();
		custom.setSchedule(NotificationPreferences.Schedule.CUSTOM);
		NotificationWindow window = NotificationWindow.of(custom.sanitized(), WEEKDAYS, RelativeDate.Basis.CALENDAR);
		assertThat(window.isOpen(monday(3, 0))).isTrue();
		assertThat(window.isOpen(saturday(12, 0))).isFalse();
	}

	@Test
	void outsideTheWindowMailAndPushAreQuietButSecurityStillDelivers() {
		NotificationPreferences quiet = prefs().quiet();
		assertThat(quiet.deliversEmail("mentions")).isFalse();
		assertThat(quiet.deliversPush("mentions")).isFalse();
		assertThat(quiet.deliversEmail(NotificationPreferences.LOCKED)).isTrue();
		assertThat(quiet.deliversPush(NotificationPreferences.LOCKED)).isTrue();
	}

	@Test
	void sanitizingSortsDaysAndKeepsOnlyARealWindow() {
		NotificationPreferences prefs = prefs();
		prefs.setWeekdays(Arrays.asList(FRIDAY, MONDAY, null, FRIDAY));
		prefs.setFrom("9:00");
		prefs.setUntil("nonsense");
		NotificationPreferences sanitized = prefs.sanitized();
		assertThat(sanitized.getWeekdays()).containsExactly(MONDAY, FRIDAY);
		assertThat(sanitized.getFrom()).isNull();
		assertThat(sanitized.getUntil()).isNull();

		prefs.setFrom("08:15");
		prefs.setUntil("08:15");
		assertThat(prefs.sanitized().getFrom()).isNull();

		prefs.setUntil("18:45");
		assertThat(prefs.sanitized().getFrom()).isEqualTo("08:15");
		assertThat(prefs.sanitized().getUntil()).isEqualTo("18:45");

		prefs.setWeekdays(List.of());
		assertThat(prefs.sanitized().getWeekdays()).isNull();
	}
}
