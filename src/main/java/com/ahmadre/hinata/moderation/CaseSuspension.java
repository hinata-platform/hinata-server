package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;

import java.time.Instant;

/**
 * The suspension a case decided, as the case keeps it (HIN-172): whom, until when, and how it
 * ended. While it runs, retention leaves the case alone.
 *
 * @param until     null for until further notice
 * @param liftedAt  when it ended; null while it runs
 * @param liftedHow how it ended
 */
public record CaseSuspension(String personId, Instant until, Instant at, Instant liftedAt,
		SuspensionService.LiftReason liftedHow) {

	public boolean running() {
		return liftedAt == null;
	}
}
