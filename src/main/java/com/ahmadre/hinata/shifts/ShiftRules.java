package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The limits and the shape rules of events and shifts (HIN-43, rules 1 to 7), as static functions
 * without Spring, so each is a plain unit test and the service, the batch and later the series
 * templates (HIN-161) run the same checks.
 */
public final class ShiftRules {

	/** Longest event. */
	public static final Duration EVENT_MAX = Duration.ofDays(14);

	/** Longest shift, and so the lower bound of every window query over shifts and assignments. */
	public static final Duration SHIFT_MAX = Duration.ofHours(24);

	public static final int SHIFTS_PER_EVENT_MAX = 200;
	public static final int TITLE_MAX = 120;
	public static final int DESCRIPTION_MAX = 2000;
	public static final int LOCATION_MAX = 200;
	public static final int HELPERS_MAX = 500;

	/** Widest window of the event list and the calendar. */
	public static final Duration WINDOW_MAX = Duration.ofDays(62);

	/** Widest window of a person's own list, which is paged. */
	public static final Duration OWN_WINDOW_MAX = Duration.ofDays(366);

	/** Most days one batch creates. An event spans at most fifteen calendar days. */
	public static final int BATCH_DAYS_MAX = 31;

	public static final int PAGE_SIZE_MAX = 100;

	/** Wall-clock time in the event's zone, ISO without an offset, seconds always written. */
	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	private ShiftRules() {
	}

	/** Rule 1, for events and shifts alike. Both bounds are required. */
	public static void span(Instant startsAt, Instant endsAt) {
		if (startsAt == null || endsAt == null) {
			throw ApiException.badRequest("error.shifts.timesRequired");
		}
		if (!endsAt.isAfter(startsAt)) {
			throw ApiException.badRequest("error.shifts.endBeforeStart");
		}
	}

	/** Rules 1 and 7 for an event. */
	public static void eventSpan(Instant startsAt, Instant endsAt) {
		span(startsAt, endsAt);
		if (Duration.between(startsAt, endsAt).compareTo(EVENT_MAX) > 0) {
			throw ApiException.badRequest("error.shifts.eventTooLong");
		}
	}

	/** Rules 1, 2 and 7 for a shift inside its event. */
	public static void shiftSpan(Instant startsAt, Instant endsAt, ShiftEvent event) {
		span(startsAt, endsAt);
		if (Duration.between(startsAt, endsAt).compareTo(SHIFT_MAX) > 0) {
			throw ApiException.badRequest("error.shifts.shiftTooLong");
		}
		if (startsAt.isBefore(event.getStartsAt()) || endsAt.isAfter(event.getEndsAt())) {
			throw ApiException.badRequest("error.shifts.shiftOutsideEvent");
		}
	}

	/** Rules 3 and 4. */
	public static void helpers(int required, Integer max) {
		if (required < 1 || required > HELPERS_MAX || (max != null && (max < 1 || max > HELPERS_MAX))) {
			throw ApiException.badRequest("error.shifts.helpersOutOfRange");
		}
		if (max != null && max < required) {
			throw ApiException.badRequest("error.shifts.maxBelowRequired");
		}
	}

	/** Rule 7 for the texts: an event needs a title, a shift may go without one. */
	public static String title(String title, boolean required) {
		String trimmed = trimmed(title);
		if (trimmed == null) {
			if (required) {
				throw ApiException.badRequest("error.shifts.titleRequired");
			}
			return null;
		}
		return limited(trimmed, TITLE_MAX);
	}

	public static String description(String description) {
		return limited(trimmed(description), DESCRIPTION_MAX);
	}

	public static String location(String location) {
		return limited(trimmed(location), LOCATION_MAX);
	}

	/** A request window: in order and no wider than [max]. */
	public static void window(Instant from, Instant to, Duration max) {
		if (from == null || to == null || !to.isAfter(from)) {
			throw ApiException.badRequest("error.shifts.windowInvalid");
		}
		if (Duration.between(from, to).compareTo(max) > 0) {
			throw ApiException.badRequest("error.shifts.windowTooLarge", max.toDays());
		}
	}

	public static int pageSize(int size) {
		return Math.max(1, Math.min(size, PAGE_SIZE_MAX));
	}

	/** Instants are stored to the millisecond; a request's nanoseconds would never compare equal. */
	public static Instant stored(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
	}

	/** Wall-clock time of [instant] in [zone], as the app shows it without a zone database. */
	public static String local(Instant instant, String zone) {
		if (instant == null) {
			return null;
		}
		return LOCAL.format(LocalDateTime.ofInstant(instant, zoneOf(zone)));
	}

	/** A stored zone, or UTC for a document that somehow has none. */
	public static ZoneId zoneOf(String zone) {
		try {
			return zone == null ? ZoneId.of("UTC") : ZoneId.of(zone);
		}
		catch (RuntimeException invalid) {
			return ZoneId.of("UTC");
		}
	}

	/** A refusal the app acts on: the key travels as {@code details.code} beside the sentence. */
	public static ApiException refusal(HttpStatus status, String key, Map<String, String> details) {
		Map<String, String> withCode = new LinkedHashMap<>(details == null ? Map.of() : details);
		withCode.put("code", key);
		return new ApiException(status, key, withCode, new Object[0]);
	}

	public static ApiException refusal(HttpStatus status, String key) {
		return refusal(status, key, Map.of());
	}

	private static String trimmed(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String limited(String text, int max) {
		if (text != null && text.length() > max) {
			throw ApiException.badRequest("error.shifts.textTooLong", max);
		}
		return text;
	}
}
