package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;

/**
 * The one rule for a reason somebody types: trimmed, blank is no reason at all, and
 * at most {@link TimePolicy#LOCK_NOTE_MAX} characters.
 *
 * <p>Shared by every act that asks for a sentence — rejecting and reopening a
 * submission, asking for a correction and answering one, asking for a span to be
 * opened — because they are refused by the same rule and a copy per service would be
 * a copy that drifts.
 */
final class TimeNotes {

	private TimeNotes() {
	}

	/** The note without surrounding whitespace, or null when nothing is left. */
	static String trimmed(String note) {
		if (note == null) {
			return null;
		}
		String trimmed = note.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > TimePolicy.LOCK_NOTE_MAX) {
			throw ApiException.badRequest("error.time.noteTooLong");
		}
		return trimmed;
	}
}
