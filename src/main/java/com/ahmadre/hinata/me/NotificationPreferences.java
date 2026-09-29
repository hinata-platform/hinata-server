package com.ahmadre.hinata.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

import java.time.DayOfWeek;
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
 * <p>The days are {@link #weekdays}: the days of the week e-mail and push may
 * reach this person at all. Null until they pick their own, and null means the
 * working days where they live (see {@code user.WorkWeeks}); the bell keeps
 * everything either way, so nothing is lost on a quiet day, it just does not ring.
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

	/** Days e-mail and push may arrive, or null for the working days where the person lives. */
	private List<DayOfWeek> weekdays;

	/**
	 * What a null {@link #weekdays} works out to for this person, so the settings card can
	 * show the days that apply. Never stored — filled on the way out.
	 */
	@Transient
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<DayOfWeek> defaultWeekdays;

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
		return base;
	}

	/**
	 * These preferences as they apply on {@code today}: unchanged on one of the
	 * person's days, both channels silenced on any other. The locked event still
	 * delivers, because {@link #deliversEmail} and {@link #deliversPush} answer it
	 * before they look at a channel.
	 */
	public NotificationPreferences on(DayOfWeek today, Set<DayOfWeek> fallback) {
		boolean open = weekdays != null ? weekdays.contains(today) : fallback.contains(today);
		if (open) return this;
		NotificationPreferences quiet = new NotificationPreferences(false, false, events);
		quiet.weekdays = weekdays;
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
