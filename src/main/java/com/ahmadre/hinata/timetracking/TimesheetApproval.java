package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import lombok.Builder;
import lombok.Data;
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
 * One person's time for one project over one span, handed in.
 *
 * <p>Stored as <em>dates</em>, never as a year and a week number. A week number
 * is a rendering of a date and a lossy one — ISO week 1 of a year can start in
 * December, the American convention disagrees with the European one, and an
 * operator who switches from weekly to monthly would leave behind rows whose key
 * no longer means anything. Two dates and the type they were cut from survive
 * every policy change, which is the point below.
 *
 * <p>{@code periodType} is a <em>snapshot</em>. Switching the instance from
 * weekly to monthly must not reinterpret what has already been submitted: last
 * month's four approvals stay four weekly approvals covering exactly the days
 * they named. Nothing recomputes a stored period, and nothing migrates one.
 *
 * <p>Which is why the question "is this day frozen by an approval?" is asked as
 * containment — {@code periodStart <= date <= periodEnd} for this user and
 * project, in SUBMITTED or APPROVED — and never as "does this day's period have
 * an approval". Containment keeps answering correctly across a rhythm change and
 * across periods of the old and new grid that overlap.
 */
@Data
@Builder(toBuilder = true)
@Document("timesheet_approvals")
// One submission per person, project and period start. The unique index is what
// makes a concurrent double-submit impossible rather than unlikely: two requests
// that race both build the same key and exactly one insert survives.
//
// It also decides what a re-submission after a rejection is. There is no second
// row for the same period — the rejected one is handed back in, and its history
// carries the round trip. A row per attempt would make "is this period frozen"
// a question about the newest of several rows, which is how a withdrawn
// submission ends up still freezing a month.
@CompoundIndex(name = "user_project_period", def = "{'userId': 1, 'projectId': 1, 'periodStart': 1}",
		unique = true)
// The containment question, which is on the path of every single write to an
// entry: equality on userId and projectId, then the range on periodEnd — ESR, so
// Mongo walks the handful of this person's approvals for this project off the
// index instead of scanning the collection. periodStart is filtered in memory
// from that tiny set, which is cheaper than a second index would be.
@CompoundIndex(name = "user_project_end", def = "{'userId': 1, 'projectId': 1, 'periodEnd': 1}")
// The approver's inbox: the projects they lead, newest submission first, with
// _id as the tiebreaker so paging is deterministic. A multi-point $in on the
// leading field with the sort on the keys that follow plans as a SORT_MERGE, so
// no blocking sort.
@CompoundIndex(name = "project_submitted", def = "{'projectId': 1, 'submittedAt': -1, '_id': -1}")
// The two inboxes that have no project to lead with, and the one thing a second
// key cannot do: be a sort prefix. An administrator's inbox drops the project
// filter on purpose, and `mine` never had one — so without these the sort has no
// index to walk and Mongo reads the collection and sorts it in memory. That is
// not merely slow: a document carries up to fifty history events, and a few years
// of monthly submissions puts the sort stage past MongoDB's 32 MB ceiling, at
// which point the inbox stops answering at all rather than answering late.
@CompoundIndex(name = "submitted", def = "{'submittedAt': -1, '_id': -1}")
@CompoundIndex(name = "status_submitted", def = "{'status': 1, 'submittedAt': -1, '_id': -1}")
@CompoundIndex(name = "user_submitted", def = "{'userId': 1, 'submittedAt': -1, '_id': -1}")
@CompoundIndex(name = "user_status_submitted",
		def = "{'userId': 1, 'status': 1, 'submittedAt': -1, '_id': -1}")
public class TimesheetApproval {

	/** How many decisions one submission keeps in full before the oldest fold away. */
	public static final int MAX_HISTORY = 50;

	/** Where a submission stands. */
	public enum Status {
		/** Handed in and waiting. Freezes the period. */
		SUBMITTED,
		/** Signed off. Freezes the period; only a reopen takes it back. */
		APPROVED,
		/** Sent back with a reason. Does <em>not</em> freeze: the point is to fix it. */
		REJECTED,
		/** Taken back by the submitter before anyone decided. Does not freeze. */
		WITHDRAWN
	}

	@Id
	private String id;

	/** Whose time this is. Never the approver's. */
	private String userId;

	/**
	 * Which project's. Entries with no project are never submitted — they are
	 * private, belong to no lead, and have nobody to approve them.
	 */
	private String projectId;

	/** First day covered, inclusive. */
	private LocalDate periodStart;

	/** Last day covered, inclusive. */
	private LocalDate periodEnd;

	/** The rhythm these dates were cut from, as it stood when they were submitted. */
	private TimePolicy.ApprovalPeriod periodType;

	private Status status;

	/**
	 * The minutes the period held at the moment it was handed in.
	 *
	 * <p>A snapshot, and useful precisely because it can go out of date: an
	 * approver comparing it against what the period holds now sees that something
	 * moved after submission. It is never the number a report adds up — that is
	 * always the entries themselves.
	 */
	private int totalMinutes;

	/** When it was first handed in, and again on every re-submission. */
	private Instant submittedAt;

	/** Who approved, rejected or reopened it last; null while nobody has. */
	private String decidedBy;

	private Instant decidedAt;

	/** The reason given with the last decision — required for a rejection and a reopen. */
	private String note;

	/**
	 * Every transition, oldest first.
	 *
	 * <p>The whole round trip is the record: handed in, sent back with a reason,
	 * handed in again, signed off. Art. 16 DSGVO gives a right to have working
	 * time corrected, and a correction somebody can read the reason for is the
	 * difference between a trail and a current value.
	 */
	@Builder.Default
	private List<Event> history = new ArrayList<>();

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	/**
	 * Optimistic lock, and it is load-bearing rather than hygiene.
	 *
	 * <p>Every transition is read-check-mutate-write on the whole document, and the
	 * two that race are the two people involved: the owner presses "withdraw" as the
	 * approver presses "approve". Both read SUBMITTED, both pass their status check,
	 * and the later write wins — so the document can end up WITHDRAWN (unfrozen)
	 * while {@code TIMESHEET_APPROVED} sits in the audit log, and the losing
	 * transition's event is gone from {@link #history}. A record with a hole in it
	 * is the one thing this collection may not be. With a version the loser gets an
	 * {@code OptimisticLockingFailureException}, which the service answers as a 409.
	 *
	 * <p>No migration goes with it, and that is only true because this collection is
	 * introduced by the same change: every row it will ever hold is written with a
	 * version. A version added to an <em>existing</em> collection is a different
	 * matter — Spring Data reads a null version as "this entity is new" and tries an
	 * insert, which collides with the row's own id. Anything that writes one of these
	 * documents by hand has to write the field too; {@code DemoSeeder} does.
	 */
	@Version
	private Long version;

	/** One transition. {@code from} is null on the first submission. */
	@Data
	@Builder(toBuilder = true)
	public static class Event {
		private Instant at;
		/** Who acted. The submitter on a submit or a withdraw, an approver otherwise. */
		private String by;
		private Status from;
		private Status to;
		/** Why, when the transition requires a reason. */
		private String note;
	}

	/** Whether this submission freezes the days it covers. */
	public boolean freezes() {
		return status == Status.SUBMITTED || status == Status.APPROVED;
	}

	/** Whether {@code date} falls inside the span, both bounds included. */
	public boolean covers(LocalDate date) {
		return date != null && periodStart != null && periodEnd != null
				&& !date.isBefore(periodStart) && !date.isAfter(periodEnd);
	}

	/**
	 * Appends a transition, keeping the list inside {@link #MAX_HISTORY}.
	 *
	 * <p>The <em>oldest</em> are dropped rather than the newest, and the first
	 * surviving entry keeps its own {@code from}, so the chain still reads as a
	 * chain. Fifty round trips on one period is a conversation that has gone
	 * wrong in a way a longer list would not fix.
	 */
	public void record(Event event) {
		if (history == null) {
			history = new ArrayList<>();
		}
		history.add(event);
		while (history.size() > MAX_HISTORY) {
			history.remove(0);
		}
	}
}
