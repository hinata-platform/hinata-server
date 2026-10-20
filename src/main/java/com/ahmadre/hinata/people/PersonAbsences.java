package com.ahmadre.hinata.people;

import com.ahmadre.hinata.user.User;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Whether somebody is away today, as far as the viewer may know it (HIN-165).
 *
 * <p>Inverted for the same reason as {@code me.PersonalDataExport}: availability is read by the
 * time-tracking module and its named readers only (see {@code ModuleBoundaryTest}), and the
 * profile is core. The module implements this, asks its own switch and {@code AvailabilityAccess}
 * for the viewer's sight, and answers nothing where the viewer sees nothing. Without an
 * implementation, or with the module off, the profile shows no absence.
 */
public interface PersonAbsences {

	/** The absence of [userId] that covers [today], as [viewer] may see it; empty for none or no sight. */
	Optional<Absence> today(User viewer, String userId, LocalDate today);

	/**
	 * One absence as the profile shows it. [type] is {@code VACATION}, {@code SICK} or {@code OTHER},
	 * a sick day reading {@code OTHER} for anybody who does not see everything; [typeId] names an
	 * absence type of absence management and is left out on the same condition.
	 */
	record Absence(String type, String typeId, LocalDate from, LocalDate to, Boolean halfDay) {
	}
}
