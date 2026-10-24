package com.ahmadre.hinata.me;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every event a person can switch has a default, whether or not they ever saved a choice. */
class NotificationPreferencesTest {

	@Test
	void everyEventStartsWithAChannelOfItsOwn() {
		NotificationPreferences defaults = NotificationPreferences.defaults();
		assertThat(defaults.getEvents()).containsOnlyKeys(NotificationPreferences.EVENTS);
	}

	@Test
	void aModeratorHearsOfNewCasesByMailAndPushFromTheStart() {
		NotificationPreferences defaults = NotificationPreferences.defaults();
		assertThat(defaults.deliversEmail(NotificationPreferences.MODERATION)).isTrue();
		assertThat(defaults.deliversPush(NotificationPreferences.MODERATION)).isTrue();
	}

	@Test
	void choicesSavedBeforeModerationExistedStillDeliverIt() {
		Map<String, NotificationPreferences.Channel> saved = new LinkedHashMap<>();
		saved.put("mentions", new NotificationPreferences.Channel(false, true));
		NotificationPreferences stored = new NotificationPreferences(true, true, saved);

		NotificationPreferences read = stored.sanitized();
		assertThat(read.deliversEmail("mentions")).isFalse();
		assertThat(read.deliversEmail(NotificationPreferences.MODERATION)).isTrue();
		assertThat(read.deliversPush(NotificationPreferences.MODERATION)).isTrue();
	}
}
