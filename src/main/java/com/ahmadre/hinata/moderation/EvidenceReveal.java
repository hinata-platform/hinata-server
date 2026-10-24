package com.ahmadre.hinata.moderation;

import java.time.Instant;

/**
 * Opening what somebody without an account reported on internal content (HIN-170): a second person
 * confirms what the first asked for, or, with nobody else there, the first opens it with a reason.
 */
public record EvidenceReveal(String requestedBy, Instant requestedAt, String reason, String revealedBy,
		Instant revealedAt) {

	public boolean revealed() {
		return revealedAt != null;
	}
}
