package com.ahmadre.hinata.moderation;

import java.time.Instant;

/**
 * One step in a case's history: the first report and every step a moderator takes. Never a later
 * report, which the reports themselves record; at most {@link ModerationCase#HISTORY_MAX} per case.
 *
 * @param actorId who took the step; null for the first report, whose source stays covered
 * @param detail  what the step names, such as the reason given; no content
 */
public record CaseEvent(Instant at, Kind kind, String actorId, String detail) {

	public enum Kind {
		REPORTED,
		CLAIMED,
		RELEASED,
		REPORTER_REVEALED,
		EVIDENCE_REQUESTED,
		EVIDENCE_REVEALED,
		DECIDED,
		APPEALED,
		APPEAL_DECIDED,
		RESTORED,
		HELD,
		CONFIRMATION_REQUESTED,
		CONFIRMATION_REJECTED,
		SUSPENDED,
		SUSPENSION_LIFTED
	}
}
