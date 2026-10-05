package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A person's subscription to one of their own external calendars (HIN-94).
 *
 * <p>Personal through and through: only its owner reads it, its events and its status, and
 * nothing about it reaches a report, an inbox or another person's export. An entry taken over from
 * one of its events is an ordinary entry ({@link WorkItem.Source#CALENDAR}), and only that entry is.
 *
 * <p>The address is a credential (Google, Apple and Outlook put a private token into it), so it is
 * stored encrypted and bound to this record ({@code IcsUrlCipher}); responses, logs and the audit
 * trail carry {@link #hostMasked} and never the address.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("calendar_subscriptions")
// A person's own subscriptions, in the order they were added.
@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': 1}")
// The fetch run walks the enabled subscriptions in _id order; paused ones carry the same flag.
@CompoundIndex(name = "enabled_id", def = "{'enabled': 1, '_id': 1}")
public class CalendarSubscription {

	/** Subscriptions one person may keep. */
	public static final int PER_USER_MAX = 10;

	public static final int NAME_MAX = 80;

	/** Failures in a row after which the scheduled fetch leaves a subscription alone. */
	public static final int PAUSE_AFTER_FAILURES = 5;

	/** Skipped takeovers kept for the status list, newest first. */
	public static final int SKIPS_MAX = 20;

	/** What the last fetch came to. */
	public enum Status {
		/** Added, not fetched yet. */
		PENDING,
		/** A fetch is running. */
		RUNNING,
		OK,
		/** Read, but the window held more than is kept; the newest events are missing. */
		TRUNCATED,
		FAILED,
		/** {@value #PAUSE_AFTER_FAILURES} failures in a row: no longer fetched on schedule. */
		PAUSED
	}

	@Id
	private String id;

	private String userId;

	private String name;

	/** {@code #RRGGBB}. */
	private String color;

	/** The address, encrypted and bound to {@code calendar-subscription:<id>}. */
	private String encryptedUrl;

	/** The host and nothing else, {@code calendar.google.com}; shown as {@code calendar.google.com/…}. */
	private String hostMasked;

	private boolean enabled;

	private AutoConvert autoConvert;

	private Instant createdAt;

	private Instant updatedAt;

	// --- fetch state ----------------------------------------------------------------

	private Status lastStatus;

	/** The message key of the last failure, never the address or the feed's own words. */
	private String lastError;

	/** The argument the key takes, an HTTP status or a line number. */
	private Integer lastErrorArg;

	private Instant lastFetchedAt;

	/** Failures in a row; reset by every success and by every edit of the address. */
	private int failures;

	/** Events kept from the last successful read. */
	private int eventCount;

	private String etag;

	private String lastModified;

	/** Set while a fetch holds the subscription; a claim older than five minutes is stale. */
	private Instant fetchClaimedAt;

	/** The last refresh the person asked for, which spends the per-minute allowance. */
	private Instant manualRefreshAt;

	/** Takeovers the rule could not make, newest first, at most {@value #SKIPS_MAX}. */
	private List<Skip> skips;

	public List<Skip> getSkips() {
		return skips == null ? List.of() : skips;
	}

	public Status getLastStatus() {
		return lastStatus == null ? Status.PENDING : lastStatus;
	}

	/**
	 * The rule "take past events of this calendar over as entries".
	 *
	 * @param since only events that start on or after this moment are taken over: switching the
	 *              rule on does not file a month of history nobody looked at
	 */
	public record AutoConvert(boolean enabled, String projectId, List<String> tags, Boolean billable,
			Instant since) {

		public AutoConvert {
			tags = tags == null ? List.of() : List.copyOf(tags);
		}

		public static final AutoConvert OFF = new AutoConvert(false, null, List.of(), null, null);
	}

	/**
	 * An event the rule could not take over, and why.
	 *
	 * @param eventId    the event, while it is still in the window
	 * @param startsAt   when it started, so the list can say which one without quoting it
	 * @param messageKey the refusal, as the editor would have shown it
	 */
	public record Skip(String eventId, Instant startsAt, String messageKey, Instant at) {
	}
}
