package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.user.User;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What the module's writes publish from inside their transaction. {@link ShiftDelivery} hands them
 * on after the commit; nothing in a transaction writes an audit record or sends a notification
 * itself, so a rolled-back or retried attempt has told nobody anything.
 */
public final class ShiftDomainEvents {

	private ShiftDomainEvents() {
	}

	/**
	 * An audit record to write once the change is committed. [meta] holds ids and numbers, never a
	 * title: an event called "Farewell party for Anna" says more than the log should.
	 */
	public record Audited(AuditAction action, String actorId, String actorLabel, String targetId,
			Map<String, String> meta) {
	}

	/** How an audit record names its actor: the display name, else the address; null for the system. */
	static String label(User actor) {
		if (actor == null) {
			return null;
		}
		return actor.getDisplayName() != null ? actor.getDisplayName() : actor.getEmail();
	}

	/** Somebody got a place on a shift. HIN-45 and HIN-46 deliver from it. */
	public record Assigned(String assignmentId, String shiftId, String eventId, String projectId,
			String userId, String actorId, boolean self) {
	}

	/** Somebody's place on a shift was freed: declined, withdrawn from or taken off. */
	public record Released(String assignmentId, String shiftId, String eventId, String projectId,
			String userId, String actorId, ShiftAssignment.Status status) {
	}

	/**
	 * An event was moved or cancelled (HIN-46). The people to tell are read after the commit from
	 * the event's assignments, which a move and a cancellation both keep.
	 */
	public record EventChanged(String eventId, String projectId, String actorId, boolean cancelled,
			List<String> alsoTell) {

		public EventChanged(String eventId, String projectId, String actorId, boolean cancelled) {
			this(eventId, projectId, actorId, cancelled, List.of());
		}

		/**
		 * HIN-162: people to tell besides those still holding a place, read inside the transaction:
		 * a cancellation takes the waiting list down, so its people no longer show up afterwards.
		 */
		public EventChanged {
			alsoTell = alsoTell == null ? List.of() : List.copyOf(alsoTell);
		}
	}

	/**
	 * One shift was moved or deleted (HIN-46). [shift] is the shift as it is now, or as it was for a
	 * deleted one, and [userIds] who held a place on it, read inside the transaction: a deletion
	 * takes the assignments with it.
	 */
	public record ShiftChanged(Shift shift, String actorId, boolean deleted, List<String> userIds) {
	}

	/**
	 * A series was changed from an occurrence on, cancelled or deleted (HIN-161). [counts] says per
	 * person how many of their places it moved, cancelled or removed, read inside the transaction;
	 * each of them hears once, with that number.
	 */
	public record SeriesChanged(String projectId, String actorId, Map<String, Integer> counts) {
	}

	/**
	 * HIN-162: places may have opened on [shiftIds]: a place was freed, an upper limit went up or
	 * sign-up opened again. {@code ShiftWaitlist} moves people up after the commit, one transaction
	 * per candidate.
	 */
	public record SeatsOpened(List<String> shiftIds) {

		public SeatsOpened {
			shiftIds = List.copyOf(shiftIds);
		}

		public static SeatsOpened of(String shiftId) {
			return new SeatsOpened(List.of(shiftId));
		}

		public static SeatsOpened of(Collection<String> shiftIds) {
			return new SeatsOpened(List.copyOf(shiftIds));
		}
	}

	/** HIN-162: somebody moved up from the waiting list and has until [confirmBy] to confirm. */
	public record Promoted(String assignmentId, String shiftId, String eventId, String projectId, String userId,
			Instant confirmBy) {
	}

	/**
	 * HIN-162: a place opened, but the first person waiting already holds another shift at that
	 * time; they left the list and the place went on.
	 */
	public record WaitlistSkipped(String shiftId, String eventId, String projectId, String userId) {
	}

	/** HIN-162: somebody who moved up did not confirm in time; the place went on. */
	public record WaitlistExpired(String shiftId, String eventId, String projectId, String userId) {
	}
}
