package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One block of an event that needs people: how many it needs ({@link #requiredHelpers}), how many
 * it takes at most ({@link #maxHelpers}, null for no limit) and how many it has
 * ({@link #assignedCount}).
 *
 * <p>{@code assignedCount} is a counter, not a sum. It moves only by one, inside the transaction
 * that creates or releases an assignment, and is never written back from a count of the
 * assignments: a sum read and written back loses every assignment that commits in between. The
 * nightly {@code ShiftCounterReconcile} corrects a drifted counter with a compare-and-set on the
 * value it read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shifts")
// An event's shifts in order of start: eventId is the equality, startsAt the sort.
@CompoundIndex(name = "event_start", def = "{'eventId': 1, 'startsAt': 1}")
// The calendar without a join over events: projectId as $in over the visible projects, startsAt
// the window and the sort, _id the tiebreaker so a truncated calendar cuts at a stable place.
@CompoundIndex(name = "project_start", def = "{'projectId': 1, 'startsAt': 1, '_id': 1}")
// The nightly counter reconciliation walks the live shifts that are still running or ahead:
// deleted is the equality, startsAt the range.
@CompoundIndex(name = "live_start", def = "{'deleted': 1, 'startsAt': 1}")
public class Shift {

	@Id
	private String id;

	private String eventId;

	/** Derived by the server from the event, never read from a request. */
	private String projectId;

	private String title;

	private Instant startsAt;

	private Instant endsAt;

	@Builder.Default
	private Integer requiredHelpers = 1;

	/** Null means no upper limit. */
	private Integer maxHelpers;

	/** Changed only by a conditional update in the assignment path; see the class comment. */
	@Builder.Default
	private Integer assignedCount = 0;

	/** A copy of the event's flag, so the calendar needs no join to show it. */
	@Builder.Default
	private Boolean cancelled = Boolean.FALSE;

	/**
	 * Tombstone set before a shift is deleted. The capacity claim requires {@code deleted: false},
	 * so an assignment racing the deletion either commits before the tombstone and is deleted with
	 * the shift, or fails to claim a place: it never survives its shift.
	 */
	@Builder.Default
	private Boolean deleted = Boolean.FALSE;

	/**
	 * HIN-161: the template of the series this shift was made from. Series changes find the shift
	 * by it, never by its title; null for a shift added to one occurrence only.
	 */
	private String templateKey;

	/** HIN-46: when the last "somebody gave this shift up" notice went out, to throttle it. */
	private Instant lastShortNoticeAt;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public boolean isCancelled() {
		return Boolean.TRUE.equals(cancelled);
	}

	public boolean isDeleted() {
		return Boolean.TRUE.equals(deleted);
	}

	public int required() {
		return requiredHelpers == null ? 1 : requiredHelpers;
	}

	public int assigned() {
		return assignedCount == null ? 0 : assignedCount;
	}

	/** Fewer people than the shift needs, and not cancelled: {@link ShiftRules#shortOfPeople(Shift)}. */
	public boolean understaffed() {
		return ShiftRules.shortOfPeople(this);
	}

	/** At its upper limit; never true without one. */
	public boolean full() {
		return maxHelpers != null && assigned() >= maxHelpers;
	}
}
