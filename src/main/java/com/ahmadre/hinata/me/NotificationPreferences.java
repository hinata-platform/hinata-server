package com.ahmadre.hinata.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-user notification preferences: a per-event × per-channel grid plus two
 * independent master channel switches (so a whole channel can be silenced
 * without losing the per-event choices). Embedded in the {@code users} document.
 *
 * <p>Effective delivery is {@code master && event-channel && today is one of the
 * person's days}. The {@code security} event is transactional and locked on for
 * both channels and every day (see {@link #LOCKED}).
 *
 * <p>When e-mail and push may arrive is the {@link #schedule} (HIN-131): always, or on
 * chosen {@link #weekdays} between {@link #from} and {@link #until}. Left unset, it is the
 * organisation's default (see {@code notification.NotificationDays}); the bell keeps
 * everything either way, and what arrives outside the window is held back and sent
 * when the window opens, not dropped.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferences {

	/**
	 * Watching an issue you are neither assigned to nor reported. Its own event
	 * because it is its own decision: someone who wants every change to the two
	 * issues they subscribed to does not necessarily want every status change on
	 * the forty issues assigned to them, and vice versa. A watcher-triggered
	 * {@code ISSUE_UPDATED} is gated by this; the same notification reaching an
	 * assignee or the reporter is gated by {@code status}.
	 */
	public static final String WATCHING = "watching";

	/** Stable event ids — mirror the reference {@code account_data.js → NOTIF_EVENTS}. */
	public static final String[] EVENTS = {
			"mentions", "assigned", "comments", "status", WATCHING, "ingest", "sprint", "invites",
			"digest", "time", "security"
	};

	/** Events that can never be turned off (transactional / security mail). */
	public static final String LOCKED = "security";

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Channel {
		private boolean email;
		private boolean push;
	}

	private boolean emailEnabled = true;
	private boolean pushEnabled = true;
	private Map<String, Channel> events = new LinkedHashMap<>();

	/** Whether e-mail and push follow a schedule at all. */
	public enum Schedule {
		/** Any day, any time. */
		ALWAYS,
		/** On {@link #weekdays} between {@link #from} and {@link #until}. */
		CUSTOM
	}

	/**
	 * The person's choice, or null for the organisation's default. A null schedule with
	 * {@link #weekdays} set is a choice made before schedules existed (HIN-129): those
	 * days, all day long.
	 */
	private Schedule schedule;

	/** Days e-mail and push may arrive, or null for the working days where the person lives. */
	private List<DayOfWeek> weekdays;

	/**
	 * Start and end of the day's window as {@code HH:mm} in the person's own zone, or both
	 * null for the whole day. An end before the start runs over midnight.
	 */
	private String from;
	private String until;

	/**
	 * What a null {@link #weekdays} works out to for this person, so the settings card can
	 * show the days that apply. Never stored — filled on the way out.
	 */
	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<DayOfWeek> defaultWeekdays;

	/**
	 * The schedule, window and days that apply while the person has not chosen, so the
	 * settings card can show them and preset the fields. Never stored.
	 */
	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Schedule defaultSchedule;

	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String defaultFrom;

	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String defaultUntil;

	public NotificationPreferences(boolean emailEnabled, boolean pushEnabled, Map<String, Channel> events) {
		this.emailEnabled = emailEnabled;
		this.pushEnabled = pushEnabled;
		this.events = events;
	}

	/** Sensible defaults for a fresh account (mirrors the reference data). */
	public static NotificationPreferences defaults() {
		Map<String, Channel> events = new LinkedHashMap<>();
		events.put("mentions", new Channel(true, true));
		events.put("assigned", new Channel(true, true));
		events.put("comments", new Channel(true, false));
		events.put("status", new Channel(false, true));
		// Subscribing to an issue is an explicit, deliberate act, so both channels
		// start on — unlike "status", which every assignment opts you into.
		events.put(WATCHING, new Channel(true, true));
		events.put("ingest", new Channel(false, true)); // new issue ingested via e-mail — push on, e-mail off
		events.put("sprint", new Channel(true, true));
		events.put("invites", new Channel(true, false));
		events.put("digest", new Channel(true, false));
		// Time tracking speaks to one person about their own day, never about
		// anyone else's — a timer that ran into its ceiling, and from stage 7 an
		// approval that was decided. Push on, e-mail off: it is worth a glance,
		// not an inbox entry.
		events.put("time", new Channel(false, true));
		events.put("security", new Channel(true, true)); // locked on
		return new NotificationPreferences(true, true, events);
	}

	/**
	 * Normalises an incoming preference object: keeps only known events, fills in
	 * any missing ones from the defaults, and forces the locked {@code security}
	 * event on for both channels regardless of what the client sent.
	 */
	public NotificationPreferences sanitized() {
		NotificationPreferences base = defaults();
		base.setEmailEnabled(emailEnabled);
		base.setPushEnabled(pushEnabled);
		if (events != null) {
			for (String id : EVENTS) {
				Channel incoming = events.get(id);
				if (incoming != null && !LOCKED.equals(id)) {
					base.events.put(id, new Channel(incoming.isEmail(), incoming.isPush()));
				}
			}
		}
		base.events.put(LOCKED, new Channel(true, true));
		// Mon→Sun, each day once. None at all reads as "no choice made": silencing
		// every day is what the two channel switches are for.
		if (weekdays != null && !weekdays.isEmpty()) {
			Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
			weekdays.stream().filter(java.util.Objects::nonNull).forEach(days::add);
			base.weekdays = days.isEmpty() ? null : List.copyOf(days);
		}
		base.schedule = schedule;
		// A window needs both ends, and two different ones; anything else is the whole day.
		LocalTime start = time(from);
		LocalTime end = time(until);
		if (start != null && end != null && !start.equals(end)) {
			base.from = start.toString();
			base.until = end.toString();
		}
		return base;
	}

	/** {@code HH:mm} to a time, or null for anything that is not one. */
	public static LocalTime time(String text) {
		if (text == null || text.isBlank()) return null;
		try {
			LocalTime parsed = LocalTime.parse(text.trim());
			return parsed.withSecond(0).withNano(0);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	/**
	 * These preferences outside the person's window: both channels silenced. The locked
	 * event still delivers, because {@link #deliversEmail} and {@link #deliversPush}
	 * answer it before they look at a channel.
	 */
	public NotificationPreferences quiet() {
		NotificationPreferences quiet = new NotificationPreferences(false, false, events);
		quiet.schedule = schedule;
		quiet.weekdays = weekdays;
		quiet.from = from;
		quiet.until = until;
		return quiet;
	}

	public boolean deliversEmail(String eventId) {
		Channel channel = events == null ? null : events.get(eventId);
		return LOCKED.equals(eventId) || (emailEnabled && channel != null && channel.isEmail());
	}

	public boolean deliversPush(String eventId) {
		Channel channel = events == null ? null : events.get(eventId);
		return LOCKED.equals(eventId) || (pushEnabled && channel != null && channel.isPush());
	}
}
