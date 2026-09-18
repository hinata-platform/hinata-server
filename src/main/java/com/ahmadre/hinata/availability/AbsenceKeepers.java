package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.user.User;

/**
 * Who, besides an administrator, may write somebody else's absences.
 *
 * <p>This module has always known one answer: an administrator. Absence management 2.0 names a
 * narrower circle on purpose (HIN-116) — the people an operator puts in {@code absenceManagers} —
 * because this is where a sick day is visible as a sick day (Art. 9 DSGVO), and "every
 * administrator" is a wider circle than most operators want for that.
 *
 * <p><b>The same inversion as {@link TimeOffCatalogue} and {@link TimeOffGate}, for the same
 * reason.</b> The dependency runs {@code timeoff → availability}; this module cannot read the
 * other's settings, so it states the question and lets the module above answer it. With absence
 * management absent or switched off, {@link #adminsOnly()} answers no to everybody and the rule is
 * exactly what it was before there was a second answer.
 *
 * <p>It widens who may <em>write</em> and nothing else. What a person may <em>see</em> of somebody
 * else's absences stays where it was decided in HIN-91: {@link AvailabilityAccess#of}, the
 * visibility switch and the lead rule.
 */
public interface AbsenceKeepers {

	/** Whether [actor] keeps absences for the organisation. */
	boolean keeps(User actor);

	/** The answer when there is no absence management: administrators and nobody else. */
	static AbsenceKeepers adminsOnly() {
		return actor -> false;
	}
}
