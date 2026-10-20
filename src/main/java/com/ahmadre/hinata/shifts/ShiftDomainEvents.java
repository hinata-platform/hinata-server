package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;

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

	/** Somebody got a place on a shift. HIN-45 and HIN-46 deliver from it. */
	public record Assigned(String assignmentId, String shiftId, String eventId, String projectId,
			String userId, String actorId, boolean self) {
	}

	/** Somebody's place on a shift was freed: declined, given up or taken off. */
	public record Released(String assignmentId, String shiftId, String eventId, String projectId,
			String userId, String actorId, ShiftAssignment.Status status) {
	}
}
