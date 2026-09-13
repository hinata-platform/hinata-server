package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A person's request to have a frozen day of theirs corrected (Art. 16 DSGVO), and
 * the answer they got.
 *
 * <p>Its own collection and not the audit log, although every request and every
 * answer is audited as well. The audit log is the operator's to switch off, event by
 * event, and a correction request is not a record of what somebody did — it is
 * business data somebody is waiting on: the inbox lists it, the person reads the
 * answer, the export discloses it. None of that may depend on a logging switch.
 *
 * <p>Two kinds. {@code ENTRY} asks about an entry that exists and is frozen by the
 * lock date or a submitted period. {@code SPAN} asks for days that cannot be
 * recorded yet at all — beyond {@code maxDaysBack}, or before the lock date — and so
 * names the span instead of an entry.
 *
 * <p>The throttles are unique indexes, not a read before a write: one {@code ENTRY}
 * request per entry per day, one {@code SPAN} request per person per day. Two
 * requests racing each other cannot both get in, whatever the timing. The answer is
 * written with a condition on its absence, so a request is answered exactly once.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document("time_correction_requests")
@CompoundIndex(name = "entry_day", def = "{'workItemId': 1, 'day': 1}", unique = true,
		partialFilter = "{'kind': 'ENTRY'}")
@CompoundIndex(name = "span_user_day", def = "{'userId': 1, 'day': 1}", unique = true,
		partialFilter = "{'kind': 'SPAN'}")
// The administrator's inbox: everything, newest first.
@CompoundIndex(name = "newest", def = "{'createdAt': -1, '_id': -1}")
// A lead's inbox: requests about submitted periods of the projects they lead.
@CompoundIndex(name = "reason_project_newest",
		def = "{'reason': 1, 'projectId': 1, 'createdAt': -1, '_id': -1}")
// The person's own requests, for the export and for erasure.
@CompoundIndex(name = "user_newest", def = "{'userId': 1, 'createdAt': -1}")
// The requests about one entry, for the entry sheet.
@CompoundIndex(name = "entry_newest", def = "{'workItemId': 1, 'createdAt': -1}")
public class TimeCorrectionRequest {

	public enum Kind {
		/** About an existing, frozen entry. */
		ENTRY,
		/** About days that cannot be recorded yet. */
		SPAN
	}

	@Id
	private String id;

	private Kind kind;

	/** Who asked. Never shown as a stored name: the label is resolved when read. */
	private String userId;

	/** The entry asked about ({@code ENTRY}). */
	private String workItemId;

	/** The entry's day ({@code ENTRY}). */
	private LocalDate date;

	/** The span asked for ({@code SPAN}), both days included. */
	private LocalDate from;

	private LocalDate to;

	/** The entry's project ({@code ENTRY}); decides which leads may answer. */
	private String projectId;

	/**
	 * What stands in the way: the lock date or a submitted period for an entry; the
	 * recording limit or the lock date for a span.
	 */
	private TimePolicy.LockReason reason;

	private String note;

	/** The server's day the request was made on, in UTC — the key of the throttle. */
	private LocalDate day;

	private Instant createdAt;

	/** Null until somebody answers. */
	private Answer answer;

	/** The reply, and whether it came with the days opened for the person. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Answer {

		private String note;

		private String byId;

		private Instant at;

		/** True when the answer opened the days ({@link TimeBackfillGrant}). */
		private boolean granted;
	}
}
