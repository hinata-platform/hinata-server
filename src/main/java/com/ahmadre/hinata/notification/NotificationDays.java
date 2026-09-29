package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.common.OrganisationDayCount;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import com.ahmadre.hinata.user.WorkWeeks;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Applies a person's notification schedule (HIN-129 days, HIN-131 hours): outside their
 * window e-mail and push stay silent — held back, see {@link HeldNotifications} — and only
 * the bell records the notification at once. "Now" is now in the person's own time zone, so
 * a Friday-evening mention in Berlin is not a Saturday for somebody in Riyadh by accident —
 * nor the other way round.
 *
 * <p>Who has not chosen follows the organisation: working days as the deadline default make
 * office hours on working days everybody's default, the platform's calendar days leave
 * notifications on at all times.
 *
 * <p>The one place every delivery path asks, so the rule cannot hold for the bell fan-out
 * and be forgotten by the weekly summary or the change digest.
 */
@Component
@RequiredArgsConstructor
public class NotificationDays {

	private final SettingsService settings;
	private final OrganisationDayCount dayCount;
	private final Clock clock;

	/**
	 * The instance's zone, for people without one of their own. Read once, then kept
	 * until the settings change: a fan-out asks for every recipient, and the answer is
	 * the same for all of them.
	 */
	private volatile ZoneId instanceZone;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		instanceZone = UserZones.of(null, event.settings());
	}

	/** A person's preferences, and whether their window is open right now. */
	public record Gate(NotificationPreferences prefs, boolean open) {

		/** The preferences as they apply now: unchanged when open, both channels quiet when not. */
		public NotificationPreferences now() {
			return open ? prefs : prefs.quiet();
		}
	}

	/** The person's stored preferences, sanitized, and whether they may be reached now. */
	public Gate gate(User user) {
		NotificationPreferences prefs = stored(user);
		ZoneId zone = zoneOf(user);
		return new Gate(prefs, window(prefs, user, zone).isOpen(LocalDateTime.now(clock.withZone(zone))));
	}

	/** The preferences as they apply now — see {@link Gate#now()}. */
	public NotificationPreferences today(User user) {
		return gate(user).now();
	}

	/** The person's window as their preferences and the organisation work it out. */
	public NotificationWindow windowOf(User user) {
		return window(stored(user), user, zoneOf(user));
	}

	/**
	 * Fills in what applies while the person has not chosen — days, schedule and hours —
	 * so the settings card can show it and preset its fields.
	 */
	public void describeDefaults(User user, NotificationPreferences out) {
		ZoneId zone = zoneOf(user);
		Set<DayOfWeek> workdays = WorkWeeks.workingDays(user.getLocale(), zone);
		out.setDefaultWeekdays(List.copyOf(workdays));
		NotificationWindow fallback = NotificationWindow.defaultFor(workdays, dayCount.dayCount());
		out.setDefaultSchedule(fallback.always()
				? NotificationPreferences.Schedule.ALWAYS : NotificationPreferences.Schedule.CUSTOM);
		out.setDefaultFrom(fallback.from() == null ? null : fallback.from().toString());
		out.setDefaultUntil(fallback.until() == null ? null : fallback.until().toString());
	}

	private NotificationWindow window(NotificationPreferences prefs, User user, ZoneId zone) {
		return NotificationWindow.of(prefs, WorkWeeks.workingDays(user.getLocale(), zone), dayCount.dayCount());
	}

	private static NotificationPreferences stored(User user) {
		NotificationPreferences stored = user.getNotificationPreferences();
		return (stored == null ? NotificationPreferences.defaults() : stored).sanitized();
	}

	private ZoneId zoneOf(User user) {
		boolean own = user.getTimezone() != null && !user.getTimezone().isBlank();
		if (own) {
			return UserZones.of(user, null);
		}
		ZoneId zone = instanceZone;
		if (zone == null) {
			zone = UserZones.of(null, settings.get());
			instanceZone = zone;
		}
		return zone;
	}
}
