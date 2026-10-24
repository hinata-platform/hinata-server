package com.ahmadre.hinata.content;

import java.util.List;

/**
 * One kind of copy content leaves behind, scrubbed by the package that writes it (HIN-169): the
 * quote in a reply, the bell, the open mail digests, the history of an issue's title.
 *
 * <p>Left alone on purpose, each for its reason: the audit log (its integrity, and a retention of
 * its own), mail and pushes already sent (they cannot be fetched back), and cloned issues or copied
 * projects (content of their own with authors of their own; a case shows where it was cloned to).
 */
public interface ContentCopies {

	/**
	 * Replaces the copies of [sources] with the placeholder for [reason]. Idempotent: a copy
	 * already replaced is left as it is, and so is every copy of other content.
	 */
	void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders);
}
