package com.ahmadre.hinata.content;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where removed originals wait (HIN-169). Implemented by the moderation module, which keeps them
 * for as long as the case that took them; the surfaces only hand them over.
 *
 * <p>A removal runs in four steps and deletes last: {@link #hold} copies the objects and records
 * the current state, the surface rewrites its content, {@link #deleteOriginals} deletes what was
 * moved. When the rewrite fails, {@link #discard} undoes the first two and the originals stay.
 */
public interface Quarantine {

	/** Every quarantined object lives under this prefix; nothing but the evidence view reads it. */
	String PREFIX = "quarantine/";

	/** Where the copy of [originalKey] lies for [caseId]. */
	static String keyFor(String caseId, String originalKey) {
		return PREFIX + caseId + "/" + originalKey;
	}

	/** One object: the original, its copy, and whether the original goes once the content is rewritten. */
	record HeldObject(MediaRef original, String quarantineKey, boolean move) {
	}

	/** What {@link #hold} put away, handed back to finish or undo it. */
	record Held(String id, String caseId, List<HeldObject> objects) {

		public List<MediaRef> media() {
			return objects.stream().map(HeldObject::original).toList();
		}
	}

	/**
	 * Copies [move] and [copy] under {@code quarantine/<caseId>/} and records [fields] as they
	 * are. Throws when a copy fails, leaving nothing behind: the content has not been touched yet.
	 * The originals of [move] are deleted by {@link #deleteOriginals}; those of [copy] stay, for
	 * the other documents that still show them.
	 */
	Held hold(String caseId, ContentRef ref, RemovalScope scope, Map<String, Object> fields,
			List<MediaRef> move, List<MediaRef> copy);

	/** Keeps page versions with what [held] put away; called with at most ten at a time. */
	void keepVersions(Held held, List<VersionCopy> versions);

	/** Undoes {@link #hold} when the rewrite failed: the copies and the record go, the originals stay. */
	void discard(Held held);

	/**
	 * The steps after {@link #hold}, in the order that never loses an original: [rewrite] the
	 * content, and only then delete what moved. When the rewrite fails, the hold is undone, the
	 * originals stay and the failure goes on. Answers the keys still there, as
	 * {@link #deleteOriginals(Held)} does.
	 */
	default List<String> rewriteHeld(Held held, Runnable rewrite) {
		try {
			rewrite.run();
		}
		catch (RuntimeException ex) {
			discard(held);
			throw ex;
		}
		return deleteOriginals(held);
	}

	/** Deletes the moved originals of [held] and checks they are gone; answers the keys still there. */
	List<String> deleteOriginals(Held held);

	/** Deletes [keys] and checks they are gone, for originals a removal could not delete at once. */
	List<String> deleteOriginals(Collection<String> keys);

	/** Copies the objects of [quarantineId] back under their original keys; false if one would not copy. */
	boolean restoreObjects(String quarantineId);

	/** Hands the versions [quarantineId] keeps to [batch], oldest first, ten at a time. */
	void forEachVersions(String quarantineId, Consumer<List<VersionCopy>> batch);
}
