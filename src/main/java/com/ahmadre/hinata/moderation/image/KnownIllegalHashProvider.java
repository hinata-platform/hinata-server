package com.ahmadre.hinata.moderation.image;

import java.util.Optional;

/**
 * A seam for an operator's own subscription to one of the accredited
 * hash-matching programmes — PhotoDNA, Thorn Safer, the Google Content Safety
 * API, Cloudflare CSAM Scanning.
 *
 * <h2>Why no implementation ships, and never will</h2>
 *
 * <p>Three separate reasons, any one of which is sufficient.
 *
 * <ol>
 *   <li><b>The programmes vet the operator, not the software.</b> Access is
 *       granted to an <em>organisation</em> that has signed the programme's
 *       terms, named a responsible person and accepted a reporting obligation
 *       to a national authority. Hinata is a codebase; it cannot be the party
 *       that undertakes any of that, and shipping a client pre-wired to
 *       somebody's account would put a self-hoster inside an agreement they
 *       never entered.</li>
 *   <li><b>The terms forbid redistributing credentials.</b> There is no
 *       "bundled" form of this: an API key in a public repository is a
 *       revocation waiting to happen, and a key an operator supplies is exactly
 *       what this interface is for.</li>
 *   <li><b>The hash lists are deliberately server-side.</b> They are held by
 *       the programme and matched remotely on purpose — a local copy is an
 *       offline oracle. Anyone holding one can perturb a file until it stops
 *       matching and learn, without ever touching a network, that the result
 *       now passes. Vendoring the list to save a round trip would hand exactly
 *       that to whoever pulled the image.</li>
 * </ol>
 *
 * <p>So the shape here is the whole of what Hinata provides: a port, an empty
 * default, and a documented reason. An operator with their own accreditation
 * implements it and drops the bean on the classpath.
 *
 * <h2>Why this is not an {@link ImageModerator}</h2>
 *
 * <p>{@link ImageModerator} answers "how confident is a model that these pixels
 * are X", and {@code ModerationPolicy} turns that confidence into a decision by
 * comparing it against a tunable threshold. Nothing about this interface has
 * that shape. A match is not a score: it is a binary statement that these exact
 * bytes are already on a list that an accredited body — not this product, not
 * its operator — has adjudicated as illegal.
 *
 * <p>Everything downstream follows from that difference:
 *
 * <ul>
 *   <li><b>Never tunable.</b> There is no threshold to raise or lower, because
 *       there is no score. {@code ModerationPolicy} is not consulted.</li>
 *   <li><b>Never overridable.</b> No admin setting weakens it, in the same way
 *       {@link com.ahmadre.hinata.moderation.ModerationCategory#SEXUAL_MINORS}
 *       is already non-overridable.</li>
 *   <li><b>Never subject to failOpen.</b> An unavailable classifier degrades a
 *       verdict and queues the item; an unavailable hash provider refuses the
 *       upload. See
 *       {@link com.ahmadre.hinata.moderation.ModerationCheck#KNOWN_ILLEGAL_HASH}.</li>
 *   <li><b>Its consequence is not a queue row.</b> A match freezes the target
 *       and escalates it. A moderator is never asked to judge it on the merits,
 *       because judging it on the merits would mean looking at it.</li>
 * </ul>
 */
public interface KnownIllegalHashProvider {

	/**
	 * Whether [data] matches material the provider's programme has already
	 * adjudicated.
	 *
	 * <p>An empty result means "checked and did not match". A provider that
	 * cannot check must say so through {@link #available()} or throw — returning
	 * empty for an outage is the one answer that must never be given, because it
	 * is indistinguishable from a clean pass the product never made. That is the
	 * same rule {@link HttpImageModerator} follows for the classifier, and it
	 * matters more here: the classifier's silent bypass costs a queue row, this
	 * one costs the only check that would have caught it.
	 *
	 * @throws RuntimeException when the check could not be performed; the caller
	 *                          refuses the upload rather than degrading it
	 */
	Optional<HashMatch> match(byte[] data, String contentType);

	/**
	 * What matched, in the only two terms that may leave the provider.
	 *
	 * @param source    which programme answered, e.g. {@code photodna}. Recorded
	 *                  so a stored decision names the body that made it — an
	 *                  escalation somebody has to act on months later is
	 *                  unanswerable without knowing who to ask.
	 * @param reference the programme's own opaque handle for the match. It is
	 *                  what an authority quotes back, and it is the one field
	 *                  here that never leaves the server: not to the author, not
	 *                  to the moderation queue, not into the escalation payload.
	 *                  It is stored on the {@link
	 *                  com.ahmadre.hinata.moderation.ModerationRecord} and
	 *                  nowhere else.
	 */
	record HashMatch(String source, String reference) {
	}

	/** Identifier recorded on the verdict, so a stored decision names its origin. */
	String id();

	/**
	 * Whether the provider can currently answer.
	 *
	 * <p>Read before every upload, so implementations must make this cheap —
	 * cache a probe the way {@link HttpImageModerator} does rather than calling
	 * the programme to find out. A {@code false} here is not permission to skip
	 * the check: it refuses the upload.
	 */
	boolean available();
}
