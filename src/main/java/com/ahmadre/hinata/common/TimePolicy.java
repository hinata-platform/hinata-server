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

	/**
	 * The longest span one submission may cover: 92 days, the length of the
	 * longest calendar quarter.
	 *
	 * <p>Not a rhythm — a ceiling. It bounds the free-chosen span of
	 * {@link ApprovalPeriod#FREE}, the {@code days} of a CUSTOM_DAYS grid and the
	 * width of one lock exception, so none of the three can name a period that is
	 * larger than the largest period the grid types can produce. Here because
	 * {@code setup} and {@code config} both hold it to their inputs and neither may
	 * reach into the module.
	 */
	public static final int PERIOD_MAX_DAYS = 92;

	/**
	 * How many reopened spans one instance may carry at a time.
	 *
	 * <p>An exception is meant to be an exception: twenty is more than any
	 * operator should need, and an unbounded list would travel to every client
	 * with the policy on every read.
	 */
	public static final int LOCK_EXCEPTIONS_MAX = 20;

	/** Longest reason text on an approval decision, a reopen or a lock exception. */
	public static final int LOCK_NOTE_MAX = 1000;

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

	/**
	 * Why an entry cannot be written — one word, shared by every mechanism that
	 * freezes one.
	 *
	 * <p>After HIN-88 there are two of these and HIN-96 brings a third, and the
	 * reason they are one enum rather than three messages is what the person on
	 * the other end reads. Three mechanisms inventing their own sentence gives
	 * somebody three ways to be told "no" and three different places to look for
	 * the way out; one vocabulary gives the client one component that names the
	 * reason, who can lift it, and what to do next.
	 *
	 * <p>It travels in the {@code details} of the refusal, not only in the
	 * sentence, so the client can act on it rather than parse prose.
	 */
	public enum LockReason {
		/** The operator closed the books before a date ({@code lockBefore}). */
		LOCK_DATE,
		/** The period is submitted or approved for this person and project. */
		APPROVAL,
		/** An issued invoice references the entry (HIN-96). */
		INVOICE
	}

	/** Who can lift a freeze. Never the person whose entry it is — that is the point. */
	public enum LockHolder {
		/** An instance administrator: the lock date and its exceptions are theirs. */
		ADMIN,
		/** A lead of the project, or an administrator: approvals are theirs to reopen. */
		APPROVER,
		/** Whoever issues invoices on this instance (HIN-96). */
		ACCOUNTING
	}

	/**
	 * The documented way back. Art. 16 DSGVO asks that inaccurate personal data be
	 * corrected without undue delay, and working time is personal data — so no
	 * freeze may be a dead end, and every one of these names an act somebody can
	 * actually perform.
	 */
	public enum LockRemedy {
		/** An administrator opens a span with a reason, recorded in the audit log. */
		LOCK_EXCEPTION,
		/** An approver reopens the period with a reason, and it becomes editable again. */
		REOPEN,
		/** Accounting issues a credit note; the invoice itself is never rewritten (HIN-96). */
		CREDIT_NOTE
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
