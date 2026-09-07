package com.ahmadre.hinata.user;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.ServerSettings;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The one answer to "which time zone is this user in": the zone they set on
 * their profile, else the instance default from the general settings, else UTC.
 *
 * <p>Used wherever a calendar day has to be derived from a point in time on a
 * person's behalf — "today" for the time-tracking date rules, the day a timer
 * started, the day a commit was made. It is deliberately <em>not</em> applied to
 * stored calendar dates, which are days and stay days.
 */
public final class UserZones {

	/** Longest IANA id in the tz database is well under this; anything longer is not a zone. */
	public static final int MAX_LENGTH = 64;

	private UserZones() {
	}

	public static ZoneId of(User user, ServerSettings settings) {
		ZoneId zone = parse(user == null ? null : user.getTimezone());
		if (zone != null) {
			return zone;
		}
		zone = parse(settings == null || settings.getGeneral() == null ? null
				: settings.getGeneral().getTimezone());
		return zone != null ? zone : ZoneOffset.UTC;
	}

	/**
	 * Validates a zone a client wants stored on a profile. Blank clears the
	 * setting (returns null); anything else must be short enough to be a zone
	 * id and be one {@link ZoneId#of} accepts, or the request is a 400.
	 */
	public static String normalize(String timezone) {
		if (timezone == null) {
			return null;
		}
		String trimmed = timezone.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > MAX_LENGTH || parse(trimmed) == null) {
			throw ApiException.badRequest("error.user.invalidTimezone");
		}
		return trimmed;
	}

	private static ZoneId parse(String id) {
		if (id == null || id.isBlank() || id.length() > MAX_LENGTH) {
			return null;
		}
		try {
			return ZoneId.of(id.trim());
		}
		catch (DateTimeException notAZone) {
			return null;
		}
	}
}
