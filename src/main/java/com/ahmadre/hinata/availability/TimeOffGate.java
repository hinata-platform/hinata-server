package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.user.User;

/**
 * Whether an absence may simply be entered, or has to be asked for.
 *
 * <p>Entering one directly is how this module has always worked and how it still works: a person
 * writes the days they are away and they are away. Absence management 2.0 adds types somebody has
 * to approve (HIN-117), and for those the direct road has to close — otherwise a person could walk
 * past their own approval by using the screen that predates it.
 *
 * <p><b>The same inversion as {@link TimeOffCatalogue}, for the same reason.</b> The dependency
 * runs {@code timeoff → availability}; this module cannot ask the module above whether a type
 * needs approving, so it states the question and lets the module above answer. With absence
 * management absent or switched off, {@link #open()} answers "go ahead" — which is right, because
 * with no approvals configured there is nothing to walk past.
 *
 * <p>It refuses with 409 and the {@code reason} · {@code holder} · {@code remedy} vocabulary from
 * HIN-88, so the client can offer the way out rather than only report the wall.
 */
public interface TimeOffGate {

	/**
	 * Throws when an absence of [typeId] for [subjectId] may not be entered directly by [actor].
	 *
	 * <p>[actor] matters: somebody who keeps absences for the organisation enters them on anybody's
	 * behalf as part of the job, and asking them to file a request to themselves would be a loop.
	 */
	void assertDirectEntry(String typeId, String subjectId, User actor);

	/** The answer when there is no absence management: everything may be entered directly. */
	static TimeOffGate open() {
		return (typeId, subjectId, actor) -> {
		};
	}
}
