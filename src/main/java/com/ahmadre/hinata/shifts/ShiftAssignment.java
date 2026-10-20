package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Somebody on a shift. One document per person and shift, ever: signing up again after declining
 * or giving a shift up turns the same document back on rather than adding a second one, and the
 * unique index {@code user_shift} is what makes "the same person twice on one shift" impossible.
 *
 * <p>Times, event, project and the cancelled flag are copies of the shift, kept in step by every
 * move and cancellation, so "my shifts" and the overlap check read one collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_assignments")
// One document per person and shift, and the lookup "does this person already hold this shift".
@CompoundIndex(name = "user_shift", def = "{'userId': 1, 'shiftId': 1}", unique = true)
// "My shifts" and the overlap check: userId the equality, startsAt the window (bounded below by the
// longest shift) and the sort, _id the tiebreaker of the page order.
@CompoundIndex(name = "user_start", def = "{'userId': 1, 'startsAt': 1, '_id': 1}")
// Who is on a shift, and releasing a shift's assignments when it is deleted.
@CompoundIndex(name = "shift_status", def = "{'shiftId': 1, 'status': 1}")
// Moving and cancelling an event carries every assignment of it along.
@CompoundIndex(name = "event", def = "{'eventId': 1}")
// HIN-46: the reminder sweep reads only assignments still owed a reminder, so it walks a small
// partial index by start instead of scanning the collection.
@CompoundIndex(name = "reminder_due", def = "{'startsAt': 1}",
		partialFilter = "{'reminderSentAt': null, 'cancelled': false, 'status': {$in: ['ASSIGNED', 'CONFIRMED']}}")
// The retention run deletes assignments that ended before the cutoff: startsAt bounds the walk
// (an assignment ends at most a day after it starts), endsAt decides.
@CompoundIndex(name = "start_end", def = "{'startsAt': 1, 'endsAt': 1}")
// HIN-162: the waiting list of a shift in its order. shiftId is the equality ($in for a list of
// shifts), createdAt and _id the order; only waiting entries are in it, so it stays small.
@CompoundIndex(name = "waitlist_order", def = "{'shiftId': 1, 'createdAt': 1, '_id': 1}",
		partialFilter = "{'status': 'WAITING'}")
// HIN-162: places taken from the waiting list that still wait for a yes; the hourly job reads the
// ones whose deadline passed.
@CompoundIndex(name = "confirm_due", def = "{'confirmBy': 1}",
		partialFilter = "{'status': 'ASSIGNED', 'confirmBy': {$exists: true}}")
public class ShiftAssignment {

	public enum Status {
		/** Put on the shift by a planner, not yet confirmed by the person. */
		ASSIGNED,
		/** Confirmed by the person, or signed up by themselves. */
		CONFIRMED,
		/** The person said no to a planner's assignment. */
		DECLINED,
		/** Given up by the person or taken off by a planner. */
		RELEASED,
		/**
		 * HIN-162: on the waiting list of a full shift. Holds no place: not in
		 * {@code assignedCount}, not in the overlap check, never reminded, never named to anybody
		 * but planners.
		 */
		WAITING;

		/** The statuses that hold a place and count in {@code Shift.assignedCount}. */
		public static final List<Status> ACTIVE = List.of(ASSIGNED, CONFIRMED);

		/** HIN-162: the statuses a person can still give up: a place, or a spot on the waiting list. */
		public static final List<Status> ACTIVE_OR_WAITING = List.of(ASSIGNED, CONFIRMED, WAITING);

		/** The statuses a document can be turned back on from. */
		public static final List<Status> INACTIVE = List.of(DECLINED, RELEASED);

		public boolean active() {
			return this == ASSIGNED || this == CONFIRMED;
		}
	}

	@Id
	private String id;

	private String shiftId;

	private String eventId;

	private String projectId;

	private String userId;

	private Instant startsAt;

	private Instant endsAt;

	@Builder.Default
	private Boolean cancelled = Boolean.FALSE;

	@Builder.Default
	private Status status = Status.ASSIGNED;

	/** Who put the person on the shift; null when they signed up themselves. */
	private String assignedBy;

	/** HIN-46. */
	private Instant reminderSentAt;

	/** HIN-162: when the person moved up from the waiting list; null for every other place. */
	private Instant promotedAt;

	/**
	 * HIN-162: until when a person who moved up has to confirm. Set only while the place waits for
	 * that answer ({@code ASSIGNED}); confirming removes it, and when it passes the place goes to
	 * the next person.
	 */
	private Instant confirmBy;

	/**
	 * HIN-162: when the person joined the waiting list, and so the entry is one. A waiting entry
	 * that never became a place is no record of work and goes a day after the shift ends; a place
	 * taken the usual way clears it.
	 */
	private Instant waitlistedAt;

	@CreatedDate
	private Instant createdAt;

	// --- the write rules every path shares ---------------------------------------------------

	/**
	 * A new document for [userId] on [shift], with the copies of the shift and [now] as its
	 * {@code createdAt}; the caller sets status and whatever else belongs to its path.
	 */
	static ShiftAssignmentBuilder newFor(Shift shift, String userId, Instant now) {
		return ShiftAssignment.builder().shiftId(shift.getId()).eventId(shift.getEventId())
				.projectId(shift.getProjectId()).userId(userId).startsAt(shift.getStartsAt())
				.endsAt(shift.getEndsAt()).cancelled(shift.isCancelled()).createdAt(now);
	}

	/**
	 * What turning a person's existing document for [shift] back on writes besides its status: the
	 * copies of the shift as it is now, a fresh {@code createdAt} (the order of a waiting list, the
	 * time of a place) and no reminder mark from an earlier life. Sign-up, joining the waiting list
	 * and moving up all start from this.
	 */
	static Update copiesOf(Shift shift, Instant now) {
		return new Update().set("createdAt", now)
				.set("startsAt", shift.getStartsAt()).set("endsAt", shift.getEndsAt())
				.set("cancelled", shift.isCancelled()).set("eventId", shift.getEventId())
				.set("projectId", shift.getProjectId())
				.unset("reminderSentAt");
	}

	/** The audit metadata of a change to this document: ids only, never a title. */
	Map<String, String> auditMeta() {
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("assignmentId", id);
		meta.put("shiftId", shiftId);
		meta.put("eventId", eventId);
		meta.put("projectId", projectId);
		meta.put("userId", userId);
		return meta;
	}

	public boolean isCancelled() {
		return Boolean.TRUE.equals(cancelled);
	}

	public boolean active() {
		return status != null && status.active();
	}

	public boolean waiting() {
		return status == Status.WAITING;
	}
}
