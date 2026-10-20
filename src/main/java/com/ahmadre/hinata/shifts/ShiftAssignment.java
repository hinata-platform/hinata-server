package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

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
public class ShiftAssignment {

	public enum Status {
		/** Put on the shift by a planner, not yet confirmed by the person. */
		ASSIGNED,
		/** Confirmed by the person, or signed up by themselves. */
		CONFIRMED,
		/** The person said no to a planner's assignment. */
		DECLINED,
		/** Given up by the person or taken off by a planner. */
		RELEASED;

		/** The statuses that hold a place and count in {@code Shift.assignedCount}. */
		public static final List<Status> ACTIVE = List.of(ASSIGNED, CONFIRMED);

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

	@CreatedDate
	private Instant createdAt;

	public boolean isCancelled() {
		return Boolean.TRUE.equals(cancelled);
	}

	public boolean active() {
		return status != null && status.active();
	}
}
