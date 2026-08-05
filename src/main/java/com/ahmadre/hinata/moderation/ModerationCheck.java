package com.ahmadre.hinata.moderation;

/**
 * The kinds of check the pipeline runs, distinguished by one question only:
 * whether the write may proceed when the check could not run.
 *
 * <p>{@link ModerationPolicy#failOpen(ModerationSurface)} already encodes half of
 * that trade — a silent pass is acceptable where a colleague signed in and is
 * waiting, and unacceptable on external ingress where nobody is accountable. This
 * enum is the other half: some checks are not tradeable on any surface, and that
 * belongs in the policy alongside the surface rule rather than as an
 * {@code if} at the one call site that happens to know about it today.
 *
 * @see ModerationPolicy#failOpen(ModerationSurface, ModerationCheck)
 */
public enum ModerationCheck {

	/**
	 * The optional classifier tiers — an external text scorer, an image model.
	 *
	 * <p>Degradable by design: they are optional, they are the part of the
	 * pipeline an operator installs rather than the part that ships, and an
	 * unreachable one must not take the product down. The verdict is marked
	 * {@link ModerationVerdict#degraded()} and the item lands in the queue, so the
	 * bypass is a row somebody can count.
	 */
	CLASSIFIER(true),

	/**
	 * Matching uploaded bytes against material an accredited body has already
	 * adjudicated —
	 * {@link com.ahmadre.hinata.moderation.image.KnownIllegalHashProvider}.
	 *
	 * <p>Not degradable, on any surface, under any admin setting. The reasoning
	 * that makes {@link #CLASSIFIER} degradable does not transfer: there, the cost
	 * of failing closed is a colleague's upload refused during an outage, and the
	 * cost of failing open is a queue row arriving late. Here the cost of failing
	 * open is storing the one category of material the product must never hold,
	 * with no second check anywhere behind it. An operator who configured a
	 * provider asked for that check to happen; "it was down, so we skipped it" is
	 * not a weaker version of that answer, it is the opposite one.
	 */
	KNOWN_ILLEGAL_HASH(false);

	private final boolean degradable;

	ModerationCheck(boolean degradable) {
		this.degradable = degradable;
	}

	/**
	 * Whether an unavailable check of this kind may be traded for letting the
	 * write through.
	 */
	public boolean degradable() {
		return degradable;
	}
}
