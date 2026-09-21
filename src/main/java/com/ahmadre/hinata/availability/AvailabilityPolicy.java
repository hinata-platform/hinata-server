package com.ahmadre.hinata.availability;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * What this package needs to know from the module that decides visibility.
 *
 * <p>Whether leads see their members' absences follows the time-tracking policy
 * {@code leadsSeeMemberEntries}, and through which projects follows where the person recorded
 * time. This package may not depend on time tracking (see {@code timetracking.ModuleBoundaryTest}),
 * so the direction is inverted, like {@code setup/SettingsGuard}: time tracking implements this.
 * Without an implementation, leads see nothing, which is the privacy default (R2).
 */
public interface AvailabilityPolicy {

	/** Whether a project lead sees the absences of the project's members, as type and span only. */
	boolean leadsSeeMemberAbsences();

	/**
	 * The projects out of [among] that [userId] recorded time on from [since] on, counting only time
	 * nobody else could have put there. A lead sees somebody's absences only through one of these:
	 * membership alone is not enough, because whoever creates a project leads it and can add anybody
	 * to it.
	 */
	Set<String> projectsWorkedOn(String userId, Set<String> among, LocalDate since);

	/**
	 * For each of [userIds], the projects out of [among] they recorded time on from [since] on, by
	 * the same rule as {@link #projectsWorkedOn}: only time nobody else could have put there, only
	 * on issues that never changed project. People without any are absent from the map.
	 *
	 * <p>Per project, not a yes or no: a view of a group asks whether somebody worked on a project
	 * they still belong to (HIN-118), and "worked somewhere in the group" would let a project they
	 * left long ago speak for one they were added to without asking. Two queries whatever the
	 * number of people.
	 */
	Map<String, Set<String>> projectsWorkedOnBy(Collection<String> userIds, Set<String> among, LocalDate since);
}
