package com.ahmadre.hinata.common;

/**
 * The vocabulary of the time-tracking policies — the words an operator picks
 * from in the admin area and the words the environment defaults are written in.
 *
 * <p>It lives here rather than in {@code timetracking/} for the same reason
 * {@code AuditAction} and {@code pat/Scopes} carry time-tracking constants:
 * these names are a contract, not behaviour. The env defaults
 * ({@code config/HinataProperties}) and the stored overrides
 * ({@code setup/ServerSettings}) are both core, both have to spell the same
 * enum, and neither may reach into the module to get it.
 *
 * <p>What the words <em>mean</em> — which days a period covers, how a duration
 * is folded onto an increment — is the module's business and lands with the
 * features that need it (periods in HIN-88, rounding in stage 6). Nothing here
 * hardcodes a rhythm: a week, a month or a free span is what an operator
 * chooses, never what the code assumes.
 */
public final class TimePolicy {

	private TimePolicy() {
	}

	/**
	 * A hundred years, as the ceiling on a retention in months. Not a policy —
	 * an upper bound that keeps the value inside what a date can express:
	 * {@code LocalDate.minusMonths} of a few billion throws, and the purge job
	 * that will read this runs at night with nobody watching. Both the
	 * environment default and the stored override are held to it.
	 */
	public static final int RETENTION_MAX_MONTHS = 1200;

	/** How often a timesheet is submitted for approval. */
	public enum ApprovalPeriod {
		/** Calendar weeks, starting on the configured {@code weekStartsOn}. */
		WEEKLY,
		/** Every second week, counted from an anchor date. */
		BIWEEKLY,
		/** Two spans per calendar month: the 1st to the 15th, the 16th to its end. */
		SEMI_MONTHLY,
		/** Calendar months. The default: the rhythm most operators already run. */
		MONTHLY,
		/** Calendar quarters, as an accounting period usually runs. */
		QUARTERLY,
		/** A fixed number of days, counted from an anchor date. */
		CUSTOM_DAYS,
		/** No fixed rhythm — a submitter picks the span. */
		FREE
	}

	/** How a reported duration is folded onto the configured increment. */
	public enum Rounding {
		/** Report the minutes as logged. */
		NONE,
		/** Always up to the next increment. */
		UP,
		/** Always down to the previous increment. */
		DOWN,
		/** To the closest increment, halves up. */
		NEAREST
	}
}
