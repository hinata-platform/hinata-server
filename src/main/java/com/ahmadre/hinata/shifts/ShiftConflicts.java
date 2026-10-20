package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.availability.AvailabilityAccess;
import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.common.TimeRanges;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * When somebody cannot be in two places, and when they are away.
 *
 * <p>The overlap arithmetic is static and needs no Spring: the assignment path refuses the second
 * of two overlapping assignments, a move reports the collisions it caused, and both decide with
 * {@link TimeRanges#overlaps}, half-open, so 10:00 to 12:00 and 12:00 to 14:00 are back to back
 * and not a clash. Cancelled and inactive assignments occupy nobody.
 *
 * <p>The instance side is the module's one door to availability ({@code ModuleBoundaryTest} names
 * it and nobody else): whether the person is away on the shift's days. It is a hint, never a
 * refusal, it counts only absences somebody entered (a Saturday without a working pattern is not
 * an absence), and it says nothing to a caller who could not see that absence anyway.
 */
@Component
@RequiredArgsConstructor
public class ShiftConflicts {

	/** The hint a write answers with when the person is away. */
	public static final String ABSENT = "absent";

	/** The client flag of absence management, read by name: the module may not import it. */
	static final String ABSENCE_FLAG = "absence_management";

	private final CapacityService capacity;
	private final AvailabilityAccess access;
	private final FeatureFlags flags;

	/** A person on a shift that collides with another of theirs. */
	public record Collision(String userId, String shiftId, String otherShiftId, String otherProjectId) {
	}

	/** The first of [others] that holds a place in [start, end), other than on [shiftId] itself. */
	public static Optional<ShiftAssignment> firstOverlap(Instant start, Instant end, String shiftId,
			Collection<ShiftAssignment> others) {
		return others.stream()
				.filter(other -> occupies(other) && !other.getShiftId().equals(shiftId))
				.filter(other -> TimeRanges.overlaps(start, end, other.getStartsAt(), other.getEndsAt()))
				.findFirst();
	}

	/**
	 * Every collision between the assignments of [moved] (per shift id, the shift's new span) and
	 * [held], everything the same people hold around them. Each person and moved shift is reported
	 * once, with the earliest-starting shift it collides with.
	 *
	 * <p>[held] is indexed once per person and sorted by start; each moved place then looks only at
	 * that person's places starting from a day before it (no shift is longer) until it ends, so a
	 * series change moving thousands of places stays O(n log n), not moved times held.
	 */
	public static List<Collision> collisions(Collection<ShiftAssignment> moved, Collection<ShiftAssignment> held) {
		Map<String, List<ShiftAssignment>> byPerson = new HashMap<>();
		for (ShiftAssignment other : held) {
			if (occupies(other)) {
				byPerson.computeIfAbsent(other.getUserId(), id -> new ArrayList<>()).add(other);
			}
		}
		byPerson.values().forEach(list -> list.sort(Comparator.comparing(ShiftAssignment::getStartsAt)));
		List<Collision> found = new ArrayList<>();
		for (ShiftAssignment mine : moved) {
			List<ShiftAssignment> theirs = occupies(mine) ? byPerson.get(mine.getUserId()) : null;
			if (theirs == null) {
				continue;
			}
			Instant earliest = mine.getStartsAt().minus(ShiftRules.SHIFT_MAX);
			for (int i = firstStartingAtOrAfter(theirs, earliest); i < theirs.size(); i++) {
				ShiftAssignment other = theirs.get(i);
				if (!other.getStartsAt().isBefore(mine.getEndsAt())) {
					break;
				}
				if (!other.getShiftId().equals(mine.getShiftId())
						&& TimeRanges.overlaps(mine.getStartsAt(), mine.getEndsAt(), other.getStartsAt(),
								other.getEndsAt())) {
					found.add(new Collision(mine.getUserId(), mine.getShiftId(), other.getShiftId(),
							other.getProjectId()));
					break;
				}
			}
		}
		return found;
	}

	/** The index of the first place in [sorted] starting at or after [instant]. */
	private static int firstStartingAtOrAfter(List<ShiftAssignment> sorted, Instant instant) {
		int low = 0;
		int high = sorted.size();
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (sorted.get(mid).getStartsAt().isBefore(instant)) {
				low = mid + 1;
			}
			else {
				high = mid;
			}
		}
		return low;
	}

	/** Holds a place: active and not cancelled with its event. */
	public static boolean occupies(ShiftAssignment assignment) {
		return assignment.active() && !assignment.isCancelled();
	}

	/**
	 * Whether [targetId] has an entered absence on a day the span touches, in the event's zone.
	 * False whenever absence management is off or [caller] may not see the target's absences: the
	 * caller then learns nothing, not even that there was something to hide.
	 */
	public boolean absent(User caller, String targetId, Instant start, Instant end, String zone) {
		if (!flags.enabled(ABSENCE_FLAG)) {
			return false;
		}
		if (!caller.getId().equals(targetId) && access.of(caller, targetId) == AvailabilityAccess.Sight.NONE) {
			return false;
		}
		ZoneId zoneId = ShiftRules.zoneOf(zone);
		LocalDate first = LocalDate.ofInstant(start, zoneId);
		// The end is exclusive: a shift ending at midnight does not touch the next day.
		LocalDate last = LocalDate.ofInstant(end.minusNanos(1), zoneId);
		if (last.isBefore(first)) {
			last = first;
		}
		return !capacity.absencesOf(List.of(targetId), first, last).isEmpty();
	}

	/** The hint list a write carries, or null when there is nothing to say. */
	public static List<String> hints(boolean absent) {
		return absent ? List.of(ABSENT) : null;
	}

	/** What an overlap refusal names about the other place, when the caller may see it. */
	static Map<String, String> details(ShiftAssignment other) {
		return Map.of("shiftId", other.getShiftId(), "eventId", other.getEventId());
	}
}
