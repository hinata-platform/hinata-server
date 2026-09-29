package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** "Now" is now where the person is, and the default follows the organisation. */
class NotificationDaysTest {

	@Test
	void aFridayEveningInBerlinIsAlreadySaturdayInTokyo() {
		// Friday 2 October 2026, 14:00 UTC: 16:00 in Berlin, 23:00 in Tokyo.
		NotificationDays days = NotificationDaysFixture.at(Instant.parse("2026-10-02T14:00:00Z"),
				RelativeDate.Basis.WORKING);
		User berlin = User.builder().id("b").locale("de").timezone("Europe/Berlin").build();
		User tokyo = User.builder().id("t").locale("ja").timezone("Asia/Tokyo").build();

		assertThat(days.gate(berlin).open()).isTrue();
		assertThat(days.today(berlin).deliversEmail("mentions")).isTrue();
		assertThat(days.gate(tokyo).open()).isFalse();
		assertThat(days.today(tokyo).deliversEmail("mentions")).isFalse();
		// Closed is not switched off: what the person chose is still there to hold back.
		assertThat(days.gate(tokyo).prefs().deliversEmail("mentions")).isTrue();
	}

	@Test
	void aSundayInRiyadhIsAWorkingDay() {
		NotificationDays days = NotificationDaysFixture.at(Instant.parse("2026-10-04T09:00:00Z"),
				RelativeDate.Basis.WORKING);
		User riyadh = User.builder().id("r").locale("ar").timezone("Asia/Riyadh").build();
		assertThat(days.today(riyadh).deliversPush("mentions")).isTrue();
	}

	@Test
	void calendarDaysLeaveNotificationsOnAtAnyTime() {
		NotificationDays days = NotificationDaysFixture.at(Instant.parse("2026-10-03T02:00:00Z"));
		User berlin = User.builder().id("b").locale("de").timezone("Europe/Berlin").build();
		assertThat(days.gate(berlin).open()).isTrue();
	}

	@Test
	void theDefaultsAreReportedForTheSettingsCard() {
		User riyadh = User.builder().id("r").locale("ar").timezone("Asia/Riyadh").build();

		NotificationPreferences working = new NotificationPreferences();
		NotificationDaysFixture.at(NotificationDaysFixture.WEDNESDAY, RelativeDate.Basis.WORKING)
				.describeDefaults(riyadh, working);
		assertThat(working.getDefaultWeekdays()).doesNotContain(java.time.DayOfWeek.FRIDAY);
		assertThat(working.getDefaultSchedule()).isEqualTo(NotificationPreferences.Schedule.CUSTOM);
		assertThat(working.getDefaultFrom()).isEqualTo("09:00");
		assertThat(working.getDefaultUntil()).isEqualTo("17:00");

		NotificationPreferences calendar = new NotificationPreferences();
		NotificationDaysFixture.weekday().describeDefaults(riyadh, calendar);
		assertThat(calendar.getDefaultSchedule()).isEqualTo(NotificationPreferences.Schedule.ALWAYS);
		assertThat(calendar.getDefaultFrom()).isNull();
		assertThat(NotificationPreferences.defaults().getSchedule()).isNull();
	}
}
