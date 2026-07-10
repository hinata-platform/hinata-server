package com.ahmadre.hinata.me;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-user notification preferences: a per-event × per-channel grid plus two
 * independent master channel switches (so a whole channel can be silenced
 * without losing the per-event choices). Embedded in the {@code users} document.
 *
 * <p>Effective delivery is {@code master && event-channel}. The {@code security}
 * event is transactional and locked on for both channels (see {@link #LOCKED}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferences {

	/** Stable event ids — mirror the reference {@code account_data.js → NOTIF_EVENTS}. */
	public static final String[] EVENTS = {
			"mentions", "assigned", "comments", "status", "ingest", "sprint", "invites", "digest", "security"
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

	/** Sensible defaults for a fresh account (mirrors the reference data). */
	public static NotificationPreferences defaults() {
		Map<String, Channel> events = new LinkedHashMap<>();
		events.put("mentions", new Channel(true, true));
		events.put("assigned", new Channel(true, true));
		events.put("comments", new Channel(true, false));
		events.put("status", new Channel(false, true));
		events.put("ingest", new Channel(false, true)); // new issue ingested via e-mail — push on, e-mail off
		events.put("sprint", new Channel(true, true));
		events.put("invites", new Channel(true, false));
		events.put("digest", new Channel(true, false));
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
		return base;
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
