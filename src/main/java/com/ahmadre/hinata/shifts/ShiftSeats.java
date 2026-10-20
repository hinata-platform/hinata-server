package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Giving a place back, the one way every path does it: giving a shift up, declining it, a planner
 * taking somebody off, the system freeing it, and a deadline passing on the waiting list.
 *
 * <p>Runs inside the caller's transaction, after the caller flipped the assignment away from an
 * active status with a compare-and-set, so the counter moves once per place. Publishes
 * {@link ShiftDomainEvents.Released} (who hears what is decided by {@link ShiftDelivery}) and
 * {@link ShiftDomainEvents.SeatsOpened} (the waiting list moves up), both delivered after the commit.
 */
@Component
@RequiredArgsConstructor
class ShiftSeats {

	private final MongoTemplate mongo;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/**
	 * [released] gave its place back, as [to], on behalf of [actorId] (null: the system did it, and
	 * nobody hears it).
	 */
	void free(ShiftAssignment released, String actorId, ShiftAssignment.Status to) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(released.getShiftId())),
				new Update().inc("assignedCount", -1).set("updatedAt", clock.instant()), Shift.class);
		events.publishEvent(new ShiftDomainEvents.Released(released.getId(), released.getShiftId(),
				released.getEventId(), released.getProjectId(), released.getUserId(), actorId, to));
		events.publishEvent(ShiftDomainEvents.SeatsOpened.of(released.getShiftId()));
	}
}
