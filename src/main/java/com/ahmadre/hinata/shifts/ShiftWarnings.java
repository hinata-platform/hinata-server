package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who now holds two overlapping places after a move: the moved event or shift of HIN-43 and the
 * occurrences a series change carries along (HIN-161). Read after the commit, for the planner's
 * eyes only; nobody is taken off anything.
 */
@Component
@RequiredArgsConstructor
public class ShiftWarnings {

	/** What the overlap check reads of a place; nothing else comes off the wire. */
	private static final String[] FIELDS = {"userId", "shiftId", "projectId", "startsAt", "endsAt", "status",
			"cancelled"};

	private final MongoTemplate mongo;
	private final ShiftAccess access;

	/**
	 * The collisions of the active places [moved] selects. One read of those places and one read
	 * over the user_start index for everybody affected, bounded below by the longest shift. The
	 * other place is named only when the caller may see its project.
	 */
	public List<ShiftService.Warning> of(User caller, Criteria moved) {
		Query movedPlaces = Query.query(moved.and("status").in(ShiftAssignment.Status.ACTIVE)
				.and("cancelled").is(false));
		movedPlaces.fields().include(FIELDS);
		List<ShiftAssignment> mine = mongo.find(movedPlaces, ShiftAssignment.class);
		if (mine.isEmpty()) {
			return List.of();
		}
		Set<String> people = new LinkedHashSet<>();
		Instant from = null;
		Instant to = null;
		for (ShiftAssignment assignment : mine) {
			people.add(assignment.getUserId());
			from = from == null || assignment.getStartsAt().isBefore(from) ? assignment.getStartsAt() : from;
			to = to == null || assignment.getEndsAt().isAfter(to) ? assignment.getEndsAt() : to;
		}
		Query around = Query.query(Criteria.where("userId").in(people)
				.and("startsAt").lt(to).gt(from.minus(ShiftRules.SHIFT_MAX))
				.and("status").in(ShiftAssignment.Status.ACTIVE).and("cancelled").is(false));
		around.fields().include(FIELDS);
		List<ShiftAssignment> held = mongo.find(around, ShiftAssignment.class);
		List<ShiftConflicts.Collision> collisions = ShiftConflicts.collisions(mine, held);
		if (collisions.isEmpty()) {
			return List.of();
		}
		Set<String> visible = access.visibleProjectIds(caller);
		return collisions.stream()
				.map(collision -> new ShiftService.Warning(collision.userId(), collision.shiftId(),
						visible.contains(collision.otherProjectId()) ? collision.otherShiftId() : null))
				.toList();
	}
}
