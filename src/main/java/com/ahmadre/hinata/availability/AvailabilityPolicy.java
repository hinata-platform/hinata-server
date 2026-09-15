package com.ahmadre.hinata.availability;

import java.time.LocalDate;
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
	 * The projects [userId] recorded time on from [since] on, counting only time nobody else could
	 * have put there. A lead sees somebody's absences only through one of these: membership alone is
	 * not enough, because whoever creates a project leads it and can add anybody to it.
	 */
	Set<String> projectsWorkedOn(String userId, LocalDate since);
}
