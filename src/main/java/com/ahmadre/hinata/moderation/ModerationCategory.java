package com.ahmadre.hinata.moderation;

/**
 * The policy categories content is scored against.
 *
 * <p>These are not an implementation detail of whichever classifier happens to be
 * configured — they are the vocabulary of the <em>statement of reasons</em> a user
 * receives when something is refused (DSA Art. 17(3), which requires the facts
 * relied on and the contractual ground). Every provider therefore maps its own
 * taxonomy onto this enum rather than the other way around, so a rejection reads
 * the same whether it was produced by the built-in lexicon or by a hosted scorer,
 * and so switching providers cannot silently change what users are told.
 *
 * <p>Each constant carries the i18n key suffix used to build both the user-facing
 * reason ({@code moderation.reason.<key>}) and the admin queue label. The keys are
 * resolved against {@code messages*.properties} like every other message; a missing
 * one is caught by {@code MessageBundleCompletenessTest} rather than leaking a raw
 * key into a rejection dialog.
 */
public enum ModerationCategory {

	/** Sexual or pornographic material; sexualised description of adults. */
	SEXUAL("sexual"),

	/**
	 * Sexual content involving minors.
	 *
	 * <p>Deliberately a category of its own and never merged into {@link #SEXUAL}:
	 * it is the one outcome that is never tenant-configurable, never subject to the
	 * fail-open degrade path, and never auto-deleted — see
	 * {@link ModerationPolicy#isOverridable(ModerationCategory)}. The built-in
	 * lexicon can only ever raise a <em>suspicion</em> here from text; Hinata does
	 * not and must not run image hash matching or a classifier for this itself
	 * (possessing training data is the offence), so a hit means "freeze and escalate
	 * to a human", not "we detected CSAM".
	 */
	SEXUAL_MINORS("sexualMinors"),

	/** Graphic violence, gore, or glorification of violence. */
	VIOLENCE("violence"),

	/** Credible threats of violence against a person. */
	VIOLENT_THREAT("violentThreat"),

	/** Hate speech or slurs targeting a protected characteristic. */
	HATE("hate"),

	/** Harassment or bullying aimed at a person. */
	HARASSMENT("harassment"),

	/** Self-harm or suicide promotion and instruction. */
	SELF_HARM("selfHarm"),

	/** Extremist / terrorist propaganda and banned symbols. */
	EXTREMISM("extremism"),

	/** Other illegal material not covered by a more specific category. */
	ILLEGAL("illegal"),

	/**
	 * Malicious files — the malware gate's category.
	 *
	 * <p>Separate from {@link #ILLEGAL} because it is the one category whose verdict
	 * comes from a scanner rather than a judgement about expression, so it is never
	 * appealable on the merits and never routed to a content moderator.
	 */
	MALWARE("malware");

	private final String key;

	ModerationCategory(String key) {
		this.key = key;
	}

	/**
	 * The i18n key suffix, e.g. {@code sexualMinors} — combined into
	 * {@code moderation.reason.sexualMinors} for the user-facing reason.
	 */
	public String key() {
		return key;
	}

	/** The full message key for the user-facing reason shown on a refusal. */
	public String reasonKey() {
		return "moderation.reason." + key;
	}
}
