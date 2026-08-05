package com.ahmadre.hinata.moderation;

/**
 * Records what the pipeline decided, so a flagged item reaches a moderator and a
 * degraded one can be found again.
 *
 * <p>An interface rather than a direct dependency because {@link ModerationService}
 * sits on the write path of every comment and must stay free of persistence: the
 * gate has to be callable from a unit test, from the e-mail poller and from inside
 * a Mongo transaction without dragging a repository along.
 *
 * <h2>What a record may and may not contain</h2>
 *
 * <p>A record stores the <em>verdict</em> and a <em>reference</em> to the content,
 * never a copy of the content. Keeping the rejected text would create a second
 * store of the most sensitive material in the product, serving no purpose the
 * primary record does not already serve, and it would have to be carried through
 * every export and erasure path the account area already implements. A moderator
 * opens the item; they do not read it out of the queue.
 *
 * <p>The one unavoidable exception is content that was refused and therefore never
 * persisted anywhere. Those records carry a content hash and the category only —
 * enough to count how often the filter fires and to notice one account tripping it
 * repeatedly, and not enough to reconstruct what was written.
 */
public interface ModerationRecorder {

	/**
	 * What a verdict was about.
	 *
	 * @param type     entity kind, e.g. {@code issue}, {@code comment}, {@code article},
	 *                 {@code attachment}, {@code user}
	 * @param id       the entity's id, or {@code null} when the write was refused and
	 *                 no entity exists
	 * @param projectId owning project when there is one, for scoping the queue
	 * @param authorId  who wrote it, or {@code null} for externally authored ingress
	 * @param label     a short human-readable handle for the queue row (an issue key,
	 *                  a file name) — never the content itself
	 */
	record Target(String type, String id, String projectId, String authorId, String label) {

		public static Target of(String type, String id) {
			return new Target(type, id, null, null, null);
		}
	}

	/**
	 * Persists [verdict] against [target] when it warrants it.
	 *
	 * <p>Implementations must be safe to call for an {@link ModerationDecision#ALLOW}
	 * verdict and simply do nothing — every call site would otherwise need the same
	 * conditional, and one of them would eventually get it wrong.
	 *
	 * <p>Must not throw. A failure to record is a monitoring problem; it is never a
	 * reason to fail a write that policy already allowed.
	 */
	void record(ModerationVerdict verdict, ModerationSurface surface, Target target);

	/**
	 * The same, for content that was <em>refused</em>: [content] is hashed onto the
	 * record and kept nowhere else.
	 *
	 * <p>On the interface rather than only on the implementation because the one
	 * caller that needs it is {@link ModerationService}, which refuses the write and
	 * therefore has the only copy of the payload that will ever exist — a refusal
	 * recorded without it is a row saying "something was blocked", which cannot answer
	 * either question a refusal actually raises: is this the same payload being
	 * retried, and is this the same payload somebody else was refused for. Everything
	 * that <em>was</em> stored still goes through the three-argument form; the hash
	 * would only be a second identifier to keep in sync.
	 *
	 * <p>Must not throw, for the same reason as above.
	 */
	void record(ModerationVerdict verdict, ModerationSurface surface, Target target, String content);

	/**
	 * The same for refused bytes.
	 *
	 * <p>A separate method rather than one parameter both kinds of content squeeze
	 * into, because a stored hash is only worth anything if it is the hash of what was
	 * actually refused: routing a binary through a {@code String} to fit one signature
	 * hashes a lossy decoding of the file, and that value matches nothing — not the
	 * next upload of the same file, and not the scanner report it would be held
	 * against.
	 */
	void record(ModerationVerdict verdict, ModerationSurface surface, Target target, byte[] content);
}
