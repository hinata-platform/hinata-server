package com.ahmadre.hinata.moderation.image;

import com.ahmadre.hinata.moderation.ModerationCategory;

import java.util.Map;

/**
 * Classifies uploaded bytes against the policy categories.
 *
 * <p>No implementation ships enabled by default, and that is a deliberate,
 * documented position rather than an omission. A useful NSFW classifier is a
 * model file of several megabytes plus a native inference runtime; bundling one
 * into every self-hosted deployment — including the ARM and 16 KB-page Android
 * builds this project already fights with — to serve installs that may never
 * receive an image is the wrong default. Operators switch one on.
 *
 * <p><b>What must never be implemented behind this interface.</b> Child sexual
 * abuse material is not a classification problem a private company may solve for
 * itself: training or fine-tuning a detector requires possessing the training
 * data, which is the offence. Repurposing a general NSFW model as a proxy is worse
 * than useless — it fires on ordinary family photos and then requires a human to
 * look at everything it flagged, manufacturing the exact harm it was meant to
 * prevent. The only lawful routes are the gated hash-matching programmes
 * (Cloudflare CSAM Scanning, Thorn Safer, Google Content Safety API, PhotoDNA),
 * which an operator integrates as a separate, credentialed service.
 * {@link ModerationCategory#SEXUAL_MINORS} therefore reaches this pipeline only
 * from text and from human reports.
 */
public interface ImageModerator {

	/**
	 * Confidence per category for these bytes, where a missing category means "did
	 * not fire". Values are 0-100.
	 *
	 * <p>Implementations must bound their own work: an image's dimensions come from
	 * a header an attacker wrote, and decoding one before checking them is how a
	 * pixel-flood turns a classifier into an out-of-memory kill.
	 */
	Map<ModerationCategory, Integer> score(byte[] data, String contentType);

	/** Whether this implementation can judge [contentType] at all. */
	boolean supports(String contentType);

	/** Identifier recorded on the verdict, so a stored decision names its origin. */
	String id();

	/** Whether the tier is currently usable — e.g. its model loaded successfully. */
	default boolean available() {
		return true;
	}
}
