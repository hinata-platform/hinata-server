package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.user.User;

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

	/** Somebody's place on a shift was freed: declined, given up or taken off. */
	public record Released(String assignmentId, String shiftId, String eventId, String projectId,
			String userId, String actorId, ShiftAssignment.Status status) {
	}

	/**
	 * An event was moved or cancelled (HIN-46). The people to tell are read after the commit from
	 * the event's assignments, which a move and a cancellation both keep.
	 */
	public record EventChanged(String eventId, String projectId, String actorId, boolean cancelled) {
	}

	/**
	 * One shift was moved or deleted (HIN-46). [shift] is the shift as it is now, or as it was for a
	 * deleted one, and [userIds] who held a place on it, read inside the transaction: a deletion
	 * takes the assignments with it.
	 */
	public record ShiftChanged(Shift shift, String actorId, boolean deleted, List<String> userIds) {
	}
}
