package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import com.ahmadre.hinata.user.WorkWeeks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Applies a person's notification days: on a day they did not choose, e-mail and
 * push stay silent and only the bell records the notification. "Today" is today
 * in the person's own time zone, so a Friday-evening mention in Berlin is not a
 * Saturday for somebody in Riyadh by accident — nor the other way round.
 *
 * <p>The one place every delivery path asks, so the rule cannot hold for the bell
 * fan-out and be forgotten by the weekly summary or the change digest.
 */
@Component
@RequiredArgsConstructor
public class NotificationDays {

	private final SettingsService settings;
	private final Clock clock;

	/** The person's stored preferences, sanitized and applied to today. */
	public NotificationPreferences today(User user) {
		NotificationPreferences stored = user.getNotificationPreferences();
		NotificationPreferences prefs = (stored == null ? NotificationPreferences.defaults() : stored).sanitized();
		ZoneId zone = zoneOf(user);
		return prefs.on(LocalDate.now(clock.withZone(zone)).getDayOfWeek(),
				WorkWeeks.workingDays(user.getLocale(), zone));
	}

	/** The days that apply when the person has not picked any, Monday first. */
	public List<DayOfWeek> defaultsFor(User user) {
		return List.copyOf(WorkWeeks.workingDays(user.getLocale(), zoneOf(user)));
	}

	/** Only a person without a zone of their own costs a settings read. */
	private ZoneId zoneOf(User user) {
		boolean own = user.getTimezone() != null && !user.getTimezone().isBlank();
		return UserZones.of(user, own ? null : settings.get());
	}
}
