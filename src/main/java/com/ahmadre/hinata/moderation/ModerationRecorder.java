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
	 *
	 * @return the id of the row that was written, or {@code null} when nothing was
	 *         (a clean verdict) or when it could not be. Returned rather than
	 *         discarded because a freeze and an escalation both have to point at the
	 *         same row this call created, and the alternative — querying for it
	 *         afterwards — means inventing a second way to identify a record and
	 *         keeping the two in step. Every existing caller ignores it, which is
	 *         correct: recording is bookkeeping.
	 */
	String record(ModerationVerdict verdict, ModerationSurface surface, Target target);

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
	 *
	 * @return the written row's id, or {@code null} — see the three-argument form
	 */
	String record(ModerationVerdict verdict, ModerationSurface surface, Target target, String content);

	/**
	 * The same for refused bytes.
	 *
	 * <p>A separate method rather than one parameter both kinds of content squeeze
	 * into, because a stored hash is only worth anything if it is the hash of what was
	 * actually refused: routing a binary through a {@code String} to fit one signature
	 * hashes a lossy decoding of the file, and that value matches nothing — not the
	 * next upload of the same file, and not the scanner report it would be held
	 * against.
	 *
	 * @return the written row's id, or {@code null} — see the three-argument form
	 */
	String record(ModerationVerdict verdict, ModerationSurface surface, Target target, byte[] content);

	/**
	 * The same for refused bytes that matched a known-illegal hash list, carrying
	 * [externalReference] — the programme's own handle for the material.
	 *
	 * <p>A method of its own rather than a sixth parameter on the byte form,
	 * because the value it adds must not be reachable from any other call. Every
	 * other field a record carries is meant to be read by somebody: the category
	 * reaches the author, the evidence reaches the moderator, the label reaches the
	 * queue. This one is read by nobody in the product. Passing it through a shared
	 * signature would make "did this call site mean to send a reference?" a question
	 * about an argument being null, which is the shape under which it eventually
	 * gets filled in from something else.
	 *
	 * <p>Must not throw, like the rest.
	 *
	 * @param externalReference {@code source:reference} from a
	 *                          {@link com.ahmadre.hinata.moderation.image.KnownIllegalHashProvider.HashMatch};
	 *                          stored on the row and exposed by no DTO
	 * @return the written row's id, or {@code null}
	 */
	String recordKnownIllegal(ModerationVerdict verdict, ModerationSurface surface, Target target,
			byte[] content, String externalReference);
}
