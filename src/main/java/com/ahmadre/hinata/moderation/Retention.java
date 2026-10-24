package com.ahmadre.hinata.moderation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * How long a decided case is kept (HIN-170, HIN-171): the latest of the decision plus the
 * instance's months, the end of the time for an appeal, and the decision on an appeal plus the
 * months. A case that took no action keeps thirty days, with its snapshot gone at once.
 */
final class Retention {

	/** The time for an appeal after a decision. */
	static final int APPEAL_MONTHS = 6;

	/** How long a case that took no action is kept. */
	static final Duration NO_ACTION = Duration.ofDays(30);

	private Retention() {
	}

	static Instant appealUntil(Instant decidedAt) {
		return plusMonths(decidedAt, APPEAL_MONTHS);
	}

	/** Kept until after [decision] taken at [at]. */
	static Instant afterDecision(Decision decision, Instant at, int months) {
		if (!decision.acted()) {
			return at.plus(NO_ACTION);
		}
		return later(plusMonths(at, months), appealUntil(at));
	}

	/** Kept until after a step at [at] on an appeal, never earlier than [until] already says. */
	static Instant afterAppeal(Instant until, Instant at, int months) {
		return later(until, plusMonths(at, months));
	}

	static Instant plusMonths(Instant at, int months) {
		return at.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
	}

	private static Instant later(Instant one, Instant other) {
		return one == null || other != null && other.isAfter(one) ? other : one;
	}
}
