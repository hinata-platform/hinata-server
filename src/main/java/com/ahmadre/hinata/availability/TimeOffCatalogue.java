package com.ahmadre.hinata.availability;

import java.util.Optional;

/**
 * What this module needs to know about an absence type an operator defined, and nothing more.
 *
 * <p>Absence management 2.0 (HIN-116) lets an operator offer types of their own — parental leave,
 * training, time off in lieu — each with its own rules. An absence may point at one. This module
 * still stores {@link TimeOff.Type}, which is a wire and storage contract the published app is
 * frozen against, so a stored absence carries both: the id of the type it was entered under, and
 * the one of three values every client has always understood.
 *
 * <p><b>Why an interface here rather than a call there.</b> The dependency runs
 * {@code timeoff → availability} and never the other way: capacity, working patterns and holidays
 * are the foundation absence management is built on, and a foundation that reached upward could not
 * be removed. So this module states the one question it has — what kind is this type? — and the
 * module above answers it. With absence management absent or switched off, {@link #unknown()}
 * answers nothing, which is exactly right: there are no types to point at.
 */
public interface TimeOffCatalogue {

	/**
	 * The stored kind for [typeId], or empty when there is no such type — because the id is wrong,
	 * or because the module that owns the catalogue is switched off.
	 */
	Optional<TimeOff.Type> kindOf(String typeId);

	/** The answer when there is no catalogue: nothing is known about any id. */
	static TimeOffCatalogue unknown() {
		return typeId -> Optional.empty();
	}
}
