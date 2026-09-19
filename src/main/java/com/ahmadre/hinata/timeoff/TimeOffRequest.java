package com.ahmadre.hinata.timeoff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Somebody asked for time off, and what became of the asking.
 *
 * <p><b>The request is the document; the absence is its result.</b> Approving one writes exactly
 * one {@code availability.TimeOff} and exactly one {@code BOOKED} row in the ledger, and the
 * request keeps both ids. Cancelling can then undo precisely what approving did, rather than
 * searching for an absence that looks about right — the thing that goes wrong when a workflow
 * keeps no thread back to what it caused.
 *
 * <p><b>[milliDays] is a snapshot.</b> It is computed from the person's working pattern and their
 * holiday calendar at the moment they submit, and never again. Somebody who moves from five days a
 * week to four next month has not thereby changed what last month's approved leave cost them, in
 * either direction. The same reasoning froze {@code periodType} onto a timesheet approval in
 * HIN-88: a stored decision is about the world as it was.
 *
 * <p><b>[approverIds] is a snapshot too</b>, for the same reason and one more: it is what the
 * inbox reads. Deriving the audience on every read would mean a request quietly moving to somebody
 * else's inbox because a team gained a lead, and disappearing from the inbox of the person who was
 * halfway through deciding it.
 *
 * <p><b>A sick note is not a request.</b> Reporting sickness never creates one of these — it takes
 * effect at once, needs nobody's permission, and has no status to be in (§ 5 EFZG, R11). Nothing
 * in this document can refuse it, which is deliberate: there is no code path that could.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document("time_off_requests")
// The person's own list, narrowed to one status.
@CompoundIndex(name = "user_status_from", def = "{'userId': 1, 'status': 1, 'from': -1, '_id': -1}")
// The same list with no status filter, which is how both screens open. Without it `status` is a
// gap between the equality prefix and the sort key, and Mongo cannot walk one index order to get
// "newest first across every status" — it sorts the result in memory instead.
@CompoundIndex(name = "user_from", def = "{'userId': 1, 'from': -1, '_id': -1}")
// The inbox. `approverIds` is an array, so this is a multikey index and has to lead with it —
// equality first, then the status it is narrowed by, then the order it is read in (ESR).
@CompoundIndex(name = "approver_status_from", def = "{'approverIds': 1, 'status': 1, 'from': -1, '_id': -1}")
// And the unfiltered inbox, for the same reason as `user_from`.
@CompoundIndex(name = "approver_from", def = "{'approverIds': 1, 'from': -1, '_id': -1}")
// One decider's clash query: which of the requests they may see touch a span. `to` is in it
// because the overlap is two ranges — `to >= from AND from <= to` — and an index that carried
// only one of them would bound one end and walk that approver's whole history for the other.
@CompoundIndex(name = "approver_status_to_from", def = "{'approverIds': 1, 'status': 1, 'to': 1, 'from': 1}")
// The same question for a keeper, who asks it of everybody.
@CompoundIndex(name = "status_from_to", def = "{'status': 1, 'from': 1, 'to': 1}")
// § 9 BUrlG: one person's approved leave touching a span. Both ends again, and for the same
// reason as above.
@CompoundIndex(name = "user_status_to_from", def = "{'userId': 1, 'status': 1, 'to': 1, 'from': 1}")
// A type cannot be retired while requests still point at it. The id alone: nothing asks this
// question with a date, and a second key here would be paid for on every write for nothing.
@CompoundIndex(name = "type", def = "{'typeId': 1}")
public class TimeOffRequest {

	/** As long an explanation as a request carries, matching a ledger reason. */
	public static final int NOTE_MAX = TimeOffLedgerEntry.REASON_MAX;

	/** How much of its history one request keeps, oldest dropped first. */
	public static final int MAX_HISTORY = 50;

	/**
	 * Where a request stands.
	 *
	 * <p>{@link #SUBMITTED} is the only state anybody decides from; {@link #APPROVED} is the only
	 * one that has an absence behind it. The three ends differ in who ended it — the person
	 * ({@link #WITHDRAWN}), the decider ({@link #REJECTED}), or either of them after the fact
	 * ({@link #CANCELLED}) — and a workflow that collapsed them into one would lose the only
	 * question anybody asks afterwards.
	 */
	public enum Status {
		SUBMITTED, APPROVED, REJECTED, WITHDRAWN, CANCELLED;

		/** Whether a decision can still be made, which is also whether it can still be taken back. */
		public boolean open() {
			return this == SUBMITTED;
		}
	}

	@Id
	private String id;

	/** Whose absence this would be. Never the person who decides it. */
	private String userId;

	/** The catalogue entry it is filed under; the kind comes from there, never from here. */
	private String typeId;

	private LocalDate from;

	private LocalDate to;

	/** Thousandths the first day counts for; a whole day unless somebody asked for less. */
	private Integer firstDayMilliDays;

	/** The same for the last day. On a one-day span only {@link #firstDayMilliDays} is used. */
	private Integer lastDayMilliDays;

	/**
	 * What the span cost when it was submitted, in thousandths of a working day. Frozen — see the
	 * class documentation.
	 */
	private Integer milliDays;

	/** Working days inside the span, for reading the figure back rather than for arithmetic. */
	private Integer workingDays;

	/** Public holidays the span swallowed, which is why it may cost less than it spans. */
	private Integer holidays;

	/** Optional, and never asked for on a type of the sick kind. */
	private String note;

	private Status status;

	/**
	 * Who could decide this when it was submitted. A snapshot; see the class documentation.
	 *
	 * <p>Never empty and never only the person themselves: routing falls back to the
	 * administrators rather than leaving a request nobody can see.
	 */
	@Builder.Default
	private List<String> approverIds = new ArrayList<>();

	private String decidedBy;

	private Instant decidedAt;

	/**
	 * Why it was decided that way. Required on a rejection — § 7 Abs. 1 BUrlG allows one only for
	 * urgent operational reasons or somebody else's prior claim, and a refusal that names neither
	 * is not a refusal anybody can check.
	 */
	private String decisionNote;

	/**
	 * Somebody named to stand in, if the person named anybody. Informed, never asked: making a
	 * stand-in confirm would make one person's leave depend on another person's attention.
	 */
	private String substituteId;

	/** The absence approving this created, so cancelling can remove that one and no other. */
	private String timeOffId;

	/** The booking approving this made, so cancelling can reverse that one and no other. */
	private String ledgerId;

	@Builder.Default
	private List<Event> history = new ArrayList<>();

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	/**
	 * Guards a decision made twice.
	 *
	 * <p>Every transition reads the document, checks where it stands and writes it back. Two
	 * deciders pressing approve at the same moment would otherwise both pass the check and both
	 * book — and a balance that lost two days for one week off is a balance nobody trusts. The
	 * loser gets {@code OptimisticLockingFailureException}, which the service answers as 409.
	 *
	 * <p>The collection is new in this stage, so every document has a version from the start.
	 * Adding one to a collection that already holds rows is the trap noted on
	 * {@code TimesheetApproval}: a null version makes Spring Data attempt an insert.
	 */
	@Version
	private Long version;

	/** One step of the story, in the order it happened. */
	@Data
	@Builder(toBuilder = true)
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Event {

		private Instant at;

		/** Who acted. Null for something the server did on its own, such as § 9 BUrlG. */
		private String by;

		/** Null on the first entry — a request comes from nowhere. */
		private Status from;

		private Status to;

		/** The sentence that came with the step, where there was one. */
		private String note;
	}

	/** Appends [event], dropping the oldest once there are more than {@link #MAX_HISTORY}. */
	public void record(Event event) {
		if (history == null) {
			history = new ArrayList<>();
		}
		history.add(event);
		while (history.size() > MAX_HISTORY) {
			history.remove(0);
		}
	}

	/** Thousandths of a working day this request is worth, zero before it was ever computed. */
	public int milliDays() {
		return milliDays == null ? 0 : milliDays;
	}

	/** Whether [date] falls inside the span, both ends included. */
	public boolean covers(LocalDate date) {
		return date != null && from != null && to != null && !date.isBefore(from) && !date.isAfter(to);
	}
}
