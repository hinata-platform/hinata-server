package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one way onto a shift and the one way off it, for a planner's assignment and a member's own
 * sign-up alike (HIN-43), and the answers a person gives to an assignment (HIN-45).
 *
 * <p>Taking a place runs in one transaction ({@link ShiftTx}, retried on a write conflict):
 * <ol>
 * <li>write the person's {@link ShiftUserGuard}, so two sign-ups of one person run one after the
 * other;</li>
 * <li>check the person: an active account that can see the project;</li>
 * <li>check the shift: not deleted, not cancelled, open for sign-up when it is one;</li>
 * <li>check the person's other assignments for an overlap;</li>
 * <li>claim a place with a conditional increment that only matches below the upper limit;</li>
 * <li>turn the person's old document for this shift back on, or insert one.</li>
 * </ol>
 * Anything failing after the claim rolls the whole transaction back, so the counter never moves
 * without an assignment. Freeing a place flips the status with a conditional update and gives the
 * place back only when that update matched, in the same transaction.
 *
 * <p>Every change publishes its audit record as {@link ShiftDomainEvents.Audited} from inside the
 * transaction; {@link ShiftDelivery} writes it after the commit. The metadata holds ids and the
 * {@code self} flag, never a title.
 */
@Service
@RequiredArgsConstructor
public class ShiftAssignments {

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAccess access;
	private final ShiftConflicts conflicts;
	private final ShiftSettings settings;
	private final UserRepository users;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/** What taking a place produced, and the hints for whoever asked. */
	public record Assigned(ShiftAssignment assignment, Shift shift, List<String> warnings) {
	}

	/**
	 * Puts [targetUserId] on the shift. With [self] the caller signs themselves up (status
	 * {@code CONFIRMED}); otherwise the caller plans the project and the status is {@code ASSIGNED}.
	 */
	public Assigned assign(User caller, String shiftId, String targetUserId, boolean self) {
		settings.requireEnabled();
		ShiftAccess.Scoped<Shift> scoped = access.visibleShift(shiftId, caller);
		String target = self ? caller.getId() : targetUserId;
		if (!self) {
			access.requirePlanner(scoped.project(), caller);
		}
		User person = target == null ? null : users.findById(target).orElse(null);
		if (person == null || !person.isActive() || !access.canSee(scoped.project(), person)) {
			throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.assigneeNotMember");
		}
		ensureGuard(target);
		ShiftEvent event = mongo.findById(scoped.item().getEventId(), ShiftEvent.class);
		if (event == null) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.shiftUnavailable");
		}
		Assigned assigned = tx.run(() -> assignInTransaction(caller, shiftId, person, self, event));
		boolean absent = conflicts.absent(caller, target, assigned.shift().getStartsAt(),
				assigned.shift().getEndsAt(), event.getTimezone());
		return new Assigned(assigned.assignment(), assigned.shift(), ShiftConflicts.hints(absent));
	}

	private Assigned assignInTransaction(User caller, String shiftId, User person, boolean self, ShiftEvent event) {
		String target = person.getId();
		// 1. Serialise this person's sign-ups.
		mongo.findAndModify(Query.query(Criteria.where("_id").is(target)), new Update().inc("seq", 1L),
				ShiftUserGuard.class);
		// 3. The shift as this transaction sees it.
		Shift shift = mongo.findById(shiftId, Shift.class);
		if (shift == null || shift.isDeleted() || shift.isCancelled()) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.shiftUnavailable");
		}
		if (self && !event.isSelfAssignable()) {
			throw ShiftRules.refusal(HttpStatus.FORBIDDEN, "error.shifts.selfAssignDisabled");
		}
		// 4. The person's other places around this one.
		List<ShiftAssignment> around = mongo.find(Query.query(around(target, shift.getStartsAt(), shift.getEndsAt())),
				ShiftAssignment.class);
		if (around.stream().anyMatch(other -> other.getShiftId().equals(shiftId) && other.active())) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.alreadyAssigned");
		}
		Optional<ShiftAssignment> overlap = ShiftConflicts.firstOverlap(shift.getStartsAt(), shift.getEndsAt(),
				shiftId, around);
		if (overlap.isPresent()) {
			ShiftAssignment other = overlap.get();
			boolean mayName = caller.getId().equals(target)
					|| access.visibleProjectIds(caller).contains(other.getProjectId());
			throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.overlappingAssignment",
					mayName ? ShiftConflicts.details(other) : Map.of());
		}
		// 5. A place, if there is one left.
		Instant now = clock.instant();
		Shift claimed = mongo.findAndModify(Query.query(claimable(shiftId)),
				new Update().inc("assignedCount", 1).set("updatedAt", now),
				FindAndModifyOptions.options().returnNew(true), Shift.class);
		if (claimed == null) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.shiftFull");
		}
		// 6. The person's document for this shift, turned back on or new.
		ShiftAssignment.Status status = self ? ShiftAssignment.Status.CONFIRMED : ShiftAssignment.Status.ASSIGNED;
		String assignedBy = self ? null : caller.getId();
		ShiftAssignment assignment = mongo.findAndModify(
				Query.query(Criteria.where("userId").is(target).and("shiftId").is(shiftId)
						.and("status").in(ShiftAssignment.Status.DECLINED, ShiftAssignment.Status.RELEASED)),
				new Update().set("status", status).set("assignedBy", assignedBy).set("createdAt", now)
						.set("startsAt", claimed.getStartsAt()).set("endsAt", claimed.getEndsAt())
						.set("cancelled", claimed.isCancelled()).set("eventId", claimed.getEventId())
						.set("projectId", claimed.getProjectId()).unset("reminderSentAt"),
				FindAndModifyOptions.options().returnNew(true), ShiftAssignment.class);
		if (assignment == null) {
			try {
				assignment = mongo.insert(ShiftAssignment.builder().shiftId(shiftId).eventId(claimed.getEventId())
						.projectId(claimed.getProjectId()).userId(target).startsAt(claimed.getStartsAt())
						.endsAt(claimed.getEndsAt()).cancelled(claimed.isCancelled()).status(status)
						.assignedBy(assignedBy).createdAt(now).build());
			}
			catch (DuplicateKeyException twice) {
				throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.alreadyAssigned");
			}
		}
		events.publishEvent(new ShiftDomainEvents.Assigned(assignment.getId(), shiftId, claimed.getEventId(),
				claimed.getProjectId(), target, caller.getId(), self));
		audited(AuditAction.SHIFT_ASSIGNMENT_CREATED, caller, assignment, Map.of("self", String.valueOf(self)));
		return new Assigned(assignment, claimed, null);
	}

	/**
	 * The person says yes to a planner's assignment: {@code ASSIGNED} becomes {@code CONFIRMED}.
	 * Only their own; confirming twice changes nothing; an assignment that was declined or given up
	 * meanwhile is a 409.
	 */
	public ShiftAssignment confirm(User caller, String assignmentId) {
		settings.requireEnabled();
		ShiftAssignment existing = own(caller, assignmentId);
		if (existing.getStatus() == ShiftAssignment.Status.CONFIRMED) {
			return existing;
		}
		ShiftAssignment confirmed = tx.run(() -> {
			ShiftAssignment changed = mongo.findAndModify(
					Query.query(Criteria.where("_id").is(existing.getId()).and("status").is(ShiftAssignment.Status.ASSIGNED)),
					new Update().set("status", ShiftAssignment.Status.CONFIRMED),
					FindAndModifyOptions.options().returnNew(true), ShiftAssignment.class);
			if (changed != null) {
				audited(AuditAction.SHIFT_ASSIGNMENT_CONFIRMED, caller, changed, Map.of());
			}
			return changed;
		});
		if (confirmed != null) {
			return confirmed;
		}
		ShiftAssignment now = mongo.findById(existing.getId(), ShiftAssignment.class);
		if (now != null && now.getStatus() == ShiftAssignment.Status.CONFIRMED) {
			return now;
		}
		throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.assignmentNotPending");
	}

	/**
	 * The person says no to a planner's assignment: {@code ASSIGNED} becomes {@code DECLINED} and the
	 * place is free again. Only their own and only while it waits for an answer; a place they
	 * confirmed or signed up for is given up with {@link #release} instead.
	 */
	public ShiftAssignment decline(User caller, String assignmentId) {
		settings.requireEnabled();
		ShiftAssignment existing = own(caller, assignmentId);
		if (existing.getStatus() == ShiftAssignment.Status.DECLINED) {
			return existing;
		}
		ShiftAssignment declined = freeAs(caller, existing, ShiftAssignment.Status.DECLINED,
				List.of(ShiftAssignment.Status.ASSIGNED), null);
		if (declined.getStatus() != ShiftAssignment.Status.DECLINED) {
			throw ShiftRules.refusal(HttpStatus.CONFLICT, "error.shifts.assignmentNotPending");
		}
		return declined;
	}

	/**
	 * Frees a place: the person gives the shift up or a planner takes them off it
	 * ({@code RELEASED}, "Abgeben"). The person themselves or a planner of the project; anybody
	 * else gets the 404 of an assignment that is not there. Freeing what is not active changes
	 * nothing and returns the document as it is.
	 */
	public ShiftAssignment release(User caller, String assignmentId) {
		settings.requireEnabled();
		ShiftAssignment existing = assignmentId == null ? null : mongo.findById(assignmentId, ShiftAssignment.class);
		if (existing == null) {
			throw ApiException.notFound("shiftAssignment");
		}
		if (!existing.getUserId().equals(caller.getId())) {
			ShiftAccess.Scoped<Shift> scoped;
			try {
				scoped = access.visibleShift(existing.getShiftId(), caller);
			}
			catch (ApiException invisible) {
				throw ApiException.notFound("shiftAssignment");
			}
			access.requirePlanner(scoped.project(), caller);
		}
		return freeAs(caller, existing, ShiftAssignment.Status.RELEASED, ShiftAssignment.Status.ACTIVE, null);
	}

	/**
	 * Frees one assignment without a permission check, for callers that decided already: account
	 * erasure and a member leaving the project (HIN-45). No actor: nobody took the person off, the
	 * membership or the account ended. [reason] goes into the audit record.
	 */
	public ShiftAssignment releaseBySystem(ShiftAssignment assignment, String reason) {
		return freeAs(null, assignment, ShiftAssignment.Status.RELEASED, ShiftAssignment.Status.ACTIVE, reason);
	}

	/**
	 * One transaction: the status flips from one of [from] to [to] with a conditional update, and
	 * the place is given back only when that update matched, so running it twice frees the place
	 * once. Returns the document as it is afterwards, changed or not.
	 */
	private ShiftAssignment freeAs(User actor, ShiftAssignment assignment, ShiftAssignment.Status to,
			List<ShiftAssignment.Status> from, String reason) {
		return tx.run(() -> {
			ShiftAssignment released = mongo.findAndModify(
					Query.query(Criteria.where("_id").is(assignment.getId()).and("status").in(from)),
					new Update().set("status", to), FindAndModifyOptions.options().returnNew(true),
					ShiftAssignment.class);
			if (released == null) {
				return mongo.findById(assignment.getId(), ShiftAssignment.class);
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(released.getShiftId())),
					new Update().inc("assignedCount", -1).set("updatedAt", clock.instant()), Shift.class);
			String actorId = actor == null ? null : actor.getId();
			events.publishEvent(new ShiftDomainEvents.Released(released.getId(), released.getShiftId(),
					released.getEventId(), released.getProjectId(), released.getUserId(), actorId, to));
			Map<String, String> meta = new LinkedHashMap<>();
			meta.put("self", String.valueOf(released.getUserId().equals(actorId)));
			if (reason != null) {
				meta.put("reason", reason);
			}
			audited(to == ShiftAssignment.Status.DECLINED ? AuditAction.SHIFT_ASSIGNMENT_DECLINED
					: AuditAction.SHIFT_ASSIGNMENT_RELEASED, actor, released, meta);
			return released;
		});
	}

	/**
	 * The caller's own assignment, in a project they still see. Somebody else's answers 404 like a
	 * missing one: only the person answers an assignment, and a planner learns nothing from the
	 * difference that the shift view does not already tell them.
	 */
	private ShiftAssignment own(User caller, String assignmentId) {
		ShiftAssignment existing = assignmentId == null ? null : mongo.findById(assignmentId, ShiftAssignment.class);
		if (existing == null || !existing.getUserId().equals(caller.getId())) {
			throw ApiException.notFound("shiftAssignment");
		}
		try {
			access.visibleShift(existing.getShiftId(), caller);
		}
		catch (ApiException invisible) {
			throw ApiException.notFound("shiftAssignment");
		}
		return existing;
	}

	/**
	 * An audit record for a change to [assignment], aimed at the person on it. The metadata holds
	 * ids only. [actor] is null when the system freed the place.
	 */
	private void audited(AuditAction action, User actor, ShiftAssignment assignment, Map<String, String> extra) {
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("assignmentId", assignment.getId());
		meta.put("shiftId", assignment.getShiftId());
		meta.put("eventId", assignment.getEventId());
		meta.put("projectId", assignment.getProjectId());
		meta.put("userId", assignment.getUserId());
		meta.putAll(extra);
		events.publishEvent(new ShiftDomainEvents.Audited(action, actor == null ? null : actor.getId(),
				ShiftDomainEvents.label(actor), assignment.getUserId(), meta));
	}

	/**
	 * The guard document exists before the transaction that writes it, so two first sign-ups of a
	 * person conflict on an update rather than racing an insert that would end the transaction with
	 * a duplicate key.
	 */
	private void ensureGuard(String userId) {
		mongo.upsert(Query.query(Criteria.where("_id").is(userId)), new Update().setOnInsert("seq", 0L),
				ShiftUserGuard.class);
	}

	/**
	 * The person's active places that could overlap [start, end): starting before it ends and, since
	 * no shift is longer than a day, after a day before it starts. The lower bound keeps the read on
	 * the user_start index instead of walking the person's whole history.
	 */
	static Criteria around(String userId, Instant start, Instant end) {
		return Criteria.where("userId").is(userId)
				.and("startsAt").lt(end).gt(start.minus(ShiftRules.SHIFT_MAX))
				.and("status").in(ShiftAssignment.Status.ACTIVE)
				.and("cancelled").is(false);
	}

	/**
	 * The capacity claim: a live shift with no upper limit, or one whose counter is still below it.
	 * One document matched or none, decided by the database in the same write that takes the place.
	 */
	static Criteria claimable(String shiftId) {
		return Criteria.where("_id").is(shiftId).and("deleted").is(false).and("cancelled").is(false)
				.orOperator(Criteria.where("maxHelpers").is(null),
						Criteria.expr(ComparisonOperators.Lt.valueOf("assignedCount").lessThan("maxHelpers")));
	}
}
