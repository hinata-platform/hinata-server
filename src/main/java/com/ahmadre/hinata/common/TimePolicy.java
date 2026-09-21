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
	 * The shortest period after which entries may be deleted automatically: two years.
	 *
	 * <p>§ 16 Abs. 2 ArbZG and § 17 Abs. 1 MiLoG ask for working-time records to be
	 * kept for at least two years. A shorter period set by a slip of the finger — 2 for
	 * 24 — would delete them for everyone the following night, so the only values an
	 * entry retention accepts are 0 (never) and this or more.
	 */
	public static final int ENTRY_RETENTION_MIN_MONTHS = 24;

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

	/**
	 * How many people an operator may name as keepers of absence types,
	 * entitlements and balances.
	 *
	 * <p>Fifty rather than unbounded for the same reason as above: the list
	 * travels with the settings document on every admin read, and a circle of
	 * people who may see sick days as sick days is not one that grows by
	 * accident. An operator who needs more has a role question, not a list
	 * question.
	 */
	public static final int ABSENCE_MANAGERS_MAX = 50;

	/** Longest reason text on an approval decision, a reopen or a lock exception. */
	public static final int LOCK_NOTE_MAX = 1000;

	/**
	 * How many days back a day may be recorded when nothing is configured: a year.
	 *
	 * <p>A typo guard — somebody typing 2015 for 2025 — and nothing more. It is the
	 * value the 1.x routes have always enforced, and with the module off it is still
	 * the only one, because the published app knows no way past it.
	 */
	public static final int MAX_DAYS_BACK_DEFAULT = 365;

	/**
	 * Ten years, as the ceiling on {@code maxDaysBack}. Not a policy: a bound that
	 * keeps "today minus this" inside the years a stored date may carry.
	 */
	public static final int MAX_DAYS_BACK_CEILING = 3660;

	/**
	 * A year, as the ceiling on {@code lateEntryHintDays}. A hint that only fires
	 * after more than a year says nothing any statute asks about.
	 */
	public static final int LATE_ENTRY_HINT_MAX_DAYS = 365;

	/** A suggested daily target is at most the day (HIN-92). */
	public static final int MAX_DAILY_TARGET_MINUTES = 24 * 60;

	/** A suggested weekly target is at most the week (HIN-92). */
	public static final int MAX_WEEKLY_TARGET_MINUTES = 7 * 24 * 60;

	/**
	 * How much of other people's absences the team calendar shows (HIN-118).
	 *
	 * <p>{@code OFF} is the default and means the calendar does not exist: nobody
	 * sees anybody else's absences there, exactly as before. {@code BUSY_ONLY}
	 * shows that somebody is away and nothing about why. {@code TYPE} names the
	 * absence type — never sickness, which is shown as "away" on every setting,
	 * because being ill is health data (Art. 9 DSGVO).
	 *
	 * <p>The level is a ceiling. Each absence type carries its own visibility, and
	 * the calendar shows the narrower of the two, so a type an operator keeps
	 * private stays private whatever this says.
	 *
	 * <p>A calendar of who is away when is a holiday plan in the sense of § 87
	 * Abs. 1 Nr. 5 BetrVG, and the admin screen says so next to the choice.
	 */
	public enum AbsenceCalendar {
		OFF, BUSY_ONLY, TYPE
	}

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
		INVOICE,
		/**
		 * The day lies further back than {@code maxDaysBack} allows. Not a freeze of
		 * anything that exists — the entry is still to be written — but the same
		 * shape of answer, because the way out is the same act: an administrator
		 * opens the span with a reason (HIN-89, R9).
		 */
		MAX_DAYS_BACK,
		/**
		 * The absence type is one somebody has to approve, so it cannot simply be
		 * entered (HIN-117). Nothing is frozen here either — the way out is to ask
		 * rather than to write — but a client that already knows this vocabulary
		 * gets the same component and the same "and here is what to do next".
		 */
		APPROVAL_REQUIRED,
		/**
		 * The balance will not cover the absence and its type forbids going under
		 * (HIN-117). Not a freeze either — the days simply are not there — and the
		 * way out is somebody granting more, which is why its holder and its remedy
		 * are the two below rather than an approver and a reopen.
		 */
		BALANCE_EXCEEDED,
		/**
		 * The absence was approved from a request, and changes only through that request (HIN-117):
		 * its days are booked against a balance, which a direct edit would leave standing.
		 */
		REQUEST_BACKED
	}

	/** Who can lift a freeze. Never the person whose entry it is — that is the point. */
	public enum LockHolder {
		/** An instance administrator: the lock date and its exceptions are theirs. */
		ADMIN,
		/** A lead of the project, or an administrator: approvals are theirs to reopen. */
		APPROVER,
		/** Whoever issues invoices on this instance (HIN-96). */
		ACCOUNTING,
		/**
		 * Whoever keeps absences: an administrator, or somebody an operator named in
		 * {@code absenceManagers} (HIN-116). A narrower circle than the administrators
		 * on purpose — this is where sick days are visible as sick days.
		 */
		KEEPER
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
		CREDIT_NOTE,
		/**
		 * An administrator opens the days for this one person, with a reason and an
		 * expiry (HIN-89). Not a lock exception: that would open them for everyone.
		 */
		BACKFILL_GRANT,
		/**
		 * The person submits a request and somebody decides it (HIN-117). The only
		 * remedy on this list that the person themselves performs, which is why the
		 * client turns it into a button rather than a sentence about somebody else.
		 */
		REQUEST,
		/**
		 * Whoever keeps absences grants or corrects the days (HIN-117). The one remedy
		 * that adds something rather than reopening something: a balance that is short
		 * is not a door that was shut, it is a claim that was never that large.
		 */
		GRANT,
		/** The person cancels the request the absence came from, and asks again if they want to. */
		CANCEL_REQUEST
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
