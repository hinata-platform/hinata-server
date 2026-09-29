package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link NotificationDays} frozen on a Wednesday, so a test about what a
 * notification says never depends on the day the suite happens to run.
 */
public final class NotificationDaysFixture {

	/** Wednesday 30 September 2026, noon UTC. */
	public static final Instant WEDNESDAY = Instant.parse("2026-09-30T12:00:00Z");

	private NotificationDaysFixture() {
	}

	public static NotificationDays weekday() {
		return at(WEDNESDAY);
	}

	public static NotificationDays at(Instant now) {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		return new NotificationDays(settings, Clock.fixed(now, ZoneOffset.UTC));
	}
}
