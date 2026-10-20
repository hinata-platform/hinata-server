package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.PersonalNotice;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The words of the module's notifications (HIN-46), in the reader's language and time zone.
 *
 * <p>The bell and the mail name the shift, the event, the day and the time, in the event's zone,
 * and in the reader's own zone as well when that is a different clock. The push names none of it,
 * nor anybody else: it says that something about a shift happened and leaves the rest to the app,
 * because a lock screen is read by whoever holds the phone.
 */
@Component
@RequiredArgsConstructor
public class ShiftNotices {

	/** What happened, each with its {@code notify.<key>.title/body} and a push sentence. */
	public enum Kind {
		ASSIGNED("shiftAssigned", "shiftAssigned"),
		RELEASED("shiftReleased", "shiftReleased"),
		REMINDER("shiftReminder", "shiftReminder"),
		EVENT_MOVED("shiftEventMoved", "shiftEventChanged"),
		EVENT_CANCELLED("shiftEventCancelled", "shiftEventChanged"),
		SHIFT_MOVED("shiftMoved", "shiftChanged"),
		SHIFT_DELETED("shiftDeleted", "shiftChanged"),
		SHORT("shiftShort", "shiftShort"),
		/** HIN-162. The body takes the confirmation deadline as {2}. */
		PROMOTED("shiftPromoted", "shiftPromoted"),
		WAITLIST_SKIPPED("shiftWaitlistSkipped", "shiftWaitlistSkipped"),
		WAITLIST_EXPIRED("shiftWaitlistExpired", "shiftWaitlistExpired");

		final String key;
		final String pushKey;

		Kind(String key, String pushKey) {
			this.key = key;
			this.pushKey = pushKey;
		}
	}

	private final UserWords words;
	private final SettingsService serverSettings;

	/**
	 * A notice about one shift. [shiftTitle] may be null, then the event's title names it; [start]
	 * and [end] are the span the notice talks about (the shift's, or for an event the event's).
	 * [extra] are the numbers a planner's notice adds (assigned, required).
	 */
	public PersonalNotice of(User reader, Kind kind, String shiftTitle, String eventTitle, Instant start,
			Instant end, String eventZone, Object... extra) {
		Locale locale = words.localeOf(reader);
		String what = shiftTitle == null || shiftTitle.isBlank() ? eventTitle
				: words.in(locale, "notify.shift.what", shiftTitle, eventTitle);
		Object[] args = new Object[2 + extra.length];
		args[0] = what;
		args[1] = when(reader, locale, start, end, eventZone);
		System.arraycopy(extra, 0, args, 2, extra.length);
		return new PersonalNotice(
				words.in(locale, "notify." + kind.key + ".title"),
				words.in(locale, "notify." + kind.key + ".body", args),
				words.in(locale, "notify.shift.pushTitle"),
				words.in(locale, "notify." + kind.pushKey + ".push"),
				words.in(locale, "notify.cta.openShifts"));
	}

	/**
	 * HIN-161: [count] of the reader's places changed with a series. Neither the series nor a time
	 * is named, in the bell either: the places may lie on many days, and the app lists them.
	 */
	public PersonalNotice seriesChanged(User reader, int count) {
		Locale locale = words.localeOf(reader);
		return new PersonalNotice(
				words.in(locale, "notify.shiftSeriesChanged.title"),
				words.in(locale, "notify.shiftSeriesChanged.body", count),
				words.in(locale, "notify.shift.pushTitle"),
				words.in(locale, "notify.shiftSeriesChanged.push"),
				words.in(locale, "notify.cta.openShifts"));
	}

	/**
	 * HIN-162: the reader moved up from the waiting list and confirms until [confirmBy], named on
	 * the event's clock like the shift itself.
	 */
	public PersonalNotice promoted(User reader, String shiftTitle, String eventTitle, Instant start, Instant end,
			String eventZone, Instant confirmBy) {
		Locale locale = words.localeOf(reader);
		ZonedDateTime due = confirmBy.atZone(ShiftRules.zoneOf(eventZone));
		String deadline = words.in(locale, "notify.shift.deadline", words.date(locale, due.toLocalDate()),
				DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(due));
		return of(reader, Kind.PROMOTED, shiftTitle, eventTitle, start, end, eventZone, deadline);
	}

	/**
	 * The span in the event's zone, and in the reader's as well when their clock reads differently
	 * at either end. Two places in the same offset (Berlin and Paris) are one clock.
	 */
	String when(User reader, Locale locale, Instant start, Instant end, String eventZone) {
		ZoneId event = ShiftRules.zoneOf(eventZone);
		ZoneId own = UserZones.of(reader, serverSettings.get());
		String inEvent = span(locale, start, end, event);
		boolean sameClock = event.getRules().getOffset(start).equals(own.getRules().getOffset(start))
				&& event.getRules().getOffset(end).equals(own.getRules().getOffset(end));
		if (sameClock) {
			return inEvent;
		}
		return words.in(locale, "notify.shift.zones", inEvent, event.getId(), span(locale, start, end, own),
				own.getId());
	}

	private String span(Locale locale, Instant start, Instant end, ZoneId zone) {
		ZonedDateTime from = start.atZone(zone);
		ZonedDateTime to = end.atZone(zone);
		DateTimeFormatter time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
		String fromDay = words.date(locale, from.toLocalDate());
		if (from.toLocalDate().equals(to.toLocalDate())) {
			return words.in(locale, "notify.shift.span", fromDay, time.format(from), time.format(to));
		}
		return words.in(locale, "notify.shift.spanOvernight", fromDay, time.format(from),
				words.date(locale, to.toLocalDate()), time.format(to));
	}
}
