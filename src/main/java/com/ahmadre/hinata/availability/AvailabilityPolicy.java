package com.ahmadre.hinata.availability;

/**
 * What this package needs to know from the module that decides visibility.
 *
 * <p>Whether leads see their members' absences follows the time-tracking policy
 * {@code leadsSeeMemberEntries}, and this package may not depend on time tracking (see
 * {@code timetracking.ModuleBoundaryTest}). So the direction is inverted, like
 * {@code setup/SettingsGuard}: time tracking implements this. Without an implementation, leads see
 * nothing, which is the privacy default (R2).
 */
public interface AvailabilityPolicy {

	/** Whether a project lead sees the absences of the project's members, as type and span only. */
	boolean leadsSeeMemberAbsences();
}
