package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One occurrence from a subscribed calendar, kept for the window the calendar shows (HIN-94).
 *
 * <p>A cache and a suggestion, never a record: a refresh replaces the window, and the event is
 * only ever shown to {@link #userId}. What a person decided about it lives in the entry they took
 * over, which names the occurrence by {@link WorkItem.CalendarRef}.
 *
 * <p>All-day events are kept as midnight to midnight in the person's zone, with {@link #allDay}
 * set, so the grid can draw them as a band.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("calendar_events")
// One document per occurrence: what the diff upserts by.
@CompoundIndex(name = "subscription_occurrence", unique = true,
		def = "{'subscriptionId': 1, 'uid': 1, 'recurrenceId': 1}")
// The calendar window of one person.
@CompoundIndex(name = "user_starts", def = "{'userId': 1, 'startsAt': 1}")
public class CalendarEvent {

	public static final int SUMMARY_MAX = 200;

	public static final int LOCATION_MAX = 200;

	/** Occurrences one subscription keeps; beyond that it is marked truncated. */
	public static final int PER_SUBSCRIPTION_MAX = 5_000;

	@Id
	private String id;

	private String subscriptionId;

	private String userId;

	private String uid;

	/** Null for an event that belongs to no series. */
	private String recurrenceId;

	private Instant startsAt;

	private Instant endsAt;

	/** The zone the calendar wrote the event in, for showing it the way it was meant. */
	private String timezone;

	private String summary;

	private String location;

	private boolean allDay;

	/** {@code TRANSP:TRANSPARENT}: the calendar marks the time as free, so the rule leaves it alone. */
	private boolean free;

	/** {@code TENTATIVE} or {@code CONFIRMED}, or null when the calendar does not say. */
	private String status;

	/** A digest of every field above, so an unchanged occurrence costs no write. */
	private String digest;

	/** Set once the rule has dealt with this occurrence, taken over or skipped; never again after. */
	private Instant autoHandledAt;
}
