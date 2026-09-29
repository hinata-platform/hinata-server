package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** "Today" is today where the person is, not where the server is. */
class NotificationDaysTest {

	@Test
	void aFridayEveningInBerlinIsAlreadySaturdayInTokyo() {
		// Friday 2 October 2026, 18:00 UTC: 20:00 in Berlin, 03:00 Saturday in Tokyo.
		NotificationDays days = NotificationDaysFixture.at(Instant.parse("2026-10-02T18:00:00Z"));
		User berlin = User.builder().id("b").locale("de").timezone("Europe/Berlin").build();
		User tokyo = User.builder().id("t").locale("ja").timezone("Asia/Tokyo").build();

		assertThat(days.today(berlin).deliversEmail("mentions")).isTrue();
		assertThat(days.today(tokyo).deliversEmail("mentions")).isFalse();
	}

	@Test
	void aSundayInRiyadhIsAWorkingDay() {
		NotificationDays days = NotificationDaysFixture.at(Instant.parse("2026-10-04T09:00:00Z"));
		User riyadh = User.builder().id("r").locale("ar").timezone("Asia/Riyadh").build();
		assertThat(days.today(riyadh).deliversPush("mentions")).isTrue();
	}

	@Test
	void theDefaultsAreReportedForTheSettingsCard() {
		NotificationDays days = NotificationDaysFixture.weekday();
		User riyadh = User.builder().id("r").locale("ar").timezone("Asia/Riyadh").build();
		assertThat(days.defaultsFor(riyadh)).doesNotContain(java.time.DayOfWeek.FRIDAY);
		assertThat(NotificationPreferences.defaults().getWeekdays()).isNull();
	}
}
