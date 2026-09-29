package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.common.OrganisationDayCount;
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

	/** At {@code now}, on the platform's default: calendar days, so notifications at any time. */
	public static NotificationDays at(Instant now) {
		return at(now, RelativeDate.Basis.CALENDAR);
	}

	/** At {@code now}, for an organisation that counts in {@code basis}. */
	public static NotificationDays at(Instant now, RelativeDate.Basis basis) {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		OrganisationDayCount dayCount = () -> basis;
		return new NotificationDays(settings, dayCount, Clock.fixed(now, ZoneOffset.UTC));
	}
}
