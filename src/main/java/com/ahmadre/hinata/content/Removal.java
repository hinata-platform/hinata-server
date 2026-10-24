package com.ahmadre.hinata.content;

import java.util.List;
import java.util.Map;

/**
 * What one removal took away and where it went.
 *
 * @param fields         the removed fields as they were, the current state only
 * @param objects        every stored object put into the quarantine, moved or copied
 * @param quarantineId   the record in the quarantine that holds all of it
 * @param pendingDeletes originals that could not be confirmed gone yet; nothing points to them any
 *                       more, and they are retried until they are
 * @param otherUses      other content that showed an image this removal moved out of all of them,
 *                       for moderation to look at as well
 * @param copies         what the copies of the removed words are found by, for
 *                       {@link CopiesScrub}; null where the content leaves none
 */
public record Removal(
		ContentRef ref,
		RemovalScope scope,
		Map<String, Object> fields,
		List<MediaRef> objects,
		String quarantineId,
		List<String> pendingDeletes,
		List<ContentRef> otherUses,
		CopySource copies) {

	/** Nothing was left to take: the content was removed already. */
	public static Removal nothing(ContentRef ref, RemovalScope scope) {
		return new Removal(ref, scope, Map.of(), List.of(), null, List.of(), List.of(), null);
	}

	public boolean complete() {
		return pendingDeletes.isEmpty();
	}
}
