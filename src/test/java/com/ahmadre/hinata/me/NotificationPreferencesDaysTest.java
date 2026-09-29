package com.ahmadre.hinata.me;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferencesDaysTest {

	private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(MONDAY, FRIDAY);

	@Test
	void onAChosenDayNothingChanges() {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		assertThat(prefs.on(MONDAY, WEEKDAYS)).isSameAs(prefs);
	}

	@Test
	void onAnyOtherDayMailAndPushAreSilentButSecurityStillDelivers() {
		NotificationPreferences quiet = NotificationPreferences.defaults().on(SATURDAY, WEEKDAYS);
		assertThat(quiet.deliversEmail("mentions")).isFalse();
		assertThat(quiet.deliversPush("mentions")).isFalse();
		assertThat(quiet.deliversEmail(NotificationPreferences.LOCKED)).isTrue();
		assertThat(quiet.deliversPush(NotificationPreferences.LOCKED)).isTrue();
	}

	@Test
	void ownDaysWinOverTheFallback() {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.setWeekdays(List.of(SATURDAY, SUNDAY));
		NotificationPreferences sanitized = prefs.sanitized();
		assertThat(sanitized.on(SATURDAY, WEEKDAYS).deliversEmail("mentions")).isTrue();
		assertThat(sanitized.on(MONDAY, WEEKDAYS).deliversEmail("mentions")).isFalse();
	}

	@Test
	void sanitizingSortsDeduplicatesAndReadsNoDaysAsNoChoice() {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.setWeekdays(Arrays.asList(FRIDAY, MONDAY, null, FRIDAY));
		assertThat(prefs.sanitized().getWeekdays()).containsExactly(MONDAY, FRIDAY);

		prefs.setWeekdays(List.of());
		assertThat(prefs.sanitized().getWeekdays()).isNull();
	}
}
