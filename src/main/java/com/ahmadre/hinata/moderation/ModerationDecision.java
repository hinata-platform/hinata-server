package com.ahmadre.hinata.moderation;

/**
 * What the pipeline concluded about one piece of content.
 *
 * <p>Three outcomes rather than a boolean, because a binary "safe/unsafe" flag
 * forces every uncertain case into one of two wrong answers: block it (and refuse
 * a legitimate bug report that happens to say "kill the process") or allow it
 * silently (and learn about real abuse from a customer). {@link #FLAG} is the band
 * where the machine says "I am not sure" and a human decides — which is also what
 * keeps the automated path out of GDPR Art. 22's "solely automated" territory for
 * everything except the unambiguous cases.
 */
public enum ModerationDecision {

	/** Nothing matched above threshold; the content is stored and visible. */
	ALLOW,

	/**
	 * Stored and visible, but recorded in the moderation queue for a human to
	 * confirm or reverse. The author is not told and sees no difference — a flag is
	 * a suspicion, not an accusation, and telling authors about every borderline
	 * score would both teach evasion and insult people writing ordinary tickets.
	 */
	FLAG,

	/**
	 * Refused at write time. The author gets a localized statement of reasons naming
	 * the category and a route to appeal; nothing is persisted.
	 */
	BLOCK;

	/** Whether this decision stops the write. */
	public boolean isBlocking() {
		return this == BLOCK;
	}

	/** The stricter of two decisions, so a multi-part check keeps the worst outcome. */
	public ModerationDecision max(ModerationDecision other) {
		if (other == null) {
			return this;
		}
		return compareTo(other) >= 0 ? this : other;
	}
}
