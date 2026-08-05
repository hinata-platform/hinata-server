package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.moderation.ModerationCategory;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One frozen thing: preserved, unreadable to everybody, and released only by a
 * named person.
 *
 * <h2>Why a registry and not a flag on the entity</h2>
 *
 * <p>Four reasons, and the first three are fatal on their own.
 *
 * <ol>
 *   <li><b>Three of the six freezable kinds have no document.</b> An attachment
 *       is a subdocument inside {@code Issue.attachments}; a voice blob is a
 *       field on a comment; an inline image has no row at all. A boolean field
 *       expresses none of those uniformly, and the shape it forces — a switch
 *       over the target type in every reader — is the one
 *       {@code ModerationQueueService.Reference} already warns about, "which is
 *       the shape that lets one of them quietly disagree with the others".</li>
 *   <li><b>A freeze has to outlive its entity.</b> Deleting a project cascades
 *       its issues, comments, attachments and blobs. A flag dies with the
 *       document it is on, and the bytes go with it — which is exactly what a
 *       freeze exists to prevent. A row here is what lets the deletion and
 *       garbage-collection paths <em>refuse</em>.</li>
 *   <li><b>Unset would be indistinguishable from false.</b> Every document
 *       written before the field existed reads as unfrozen, with no way to tell
 *       "checked and clear" from "never asked". {@code SearchService.archivedIs}
 *       documents the same problem for the archive flag and solves it with
 *       {@code ne(true)} — but a fail-open default is acceptable for an archive
 *       and is the whole failure mode here.</li>
 *   <li><b>Enumerability.</b> "What is frozen right now" is the question an
 *       operator answers to an authority. One query against this collection, or
 *       a scan of five others.</li>
 * </ol>
 *
 * <h2>What the row does and does not hold</h2>
 *
 * <p>A pointer and the circumstances, never a copy — the same rule
 * {@link com.ahmadre.hinata.moderation.ModerationRecord} follows, and for a
 * stronger reason: a store of frozen material would be a store of exactly the
 * material nobody may read, sitting in a collection that has to survive every
 * export and erasure path in the product.
 */
@Data
@Builder
@Document("frozen_content")
// One freeze per target. The unique index — not an existence check before the
// insert — is what makes a double freeze idempotent under two concurrent
// reports, which is precisely the case this collection sees: an urgent report
// notifies every admin, and the second reporter is often seconds behind the
// first.
@CompoundIndex(name = "target", def = "{'targetType': 1, 'targetId': 1}", unique = true)
public class FrozenContent {

	@Id
	private String id;

	private FrozenTargetType targetType;

	/** Canonical id of the frozen thing, or the storage key for an {@code OBJECT}. */
	private String targetId;

	/**
	 * The issue a frozen comment or attachment belongs to. Carried for the same
	 * reason {@code ContentReport.contextId} is: an attachment cannot be located by
	 * id alone, and a comment cannot be found without its thread.
	 */
	private String contextId;

	/**
	 * Every storage key this target owns — a voice blob, an attachment's object, an
	 * inline image embedded in the frozen body.
	 *
	 * <p>Resolved once, at freeze time, and stored rather than re-derived on each
	 * read. Re-deriving would mean parsing the frozen body to find its images,
	 * which means reading the frozen body, which is the one thing the mechanism
	 * forbids. Each key is also written as its own {@code OBJECT} row so the byte
	 * guard is a set lookup; this list is what an unfreeze uses to find them again.
	 */
	@Builder.Default
	private List<String> objectKeys = List.of();

	/** The policy category the freeze was raised under. */
	private ModerationCategory category;

	/** The report that triggered it, when a person rather than a provider did. */
	private String reportId;

	/**
	 * Who reported it.
	 *
	 * <p>Kept on the freeze row and not only on the report, because the abuse
	 * question — "this account has frozen three things today, all dismissed" — has
	 * to be answerable from the freezes, and a report that is later deleted or
	 * whose target is gone would take the attribution with it. A freeze nobody can
	 * attribute is a freeze that cannot be argued about.
	 */
	@Indexed
	private String reporterId;

	/** The admin who froze it by hand, when it was not raised automatically. */
	private String frozenBy;

	private Instant frozenAt;

	/** Why — the automatic trigger's name, or an admin's own words. */
	private String reason;

	/**
	 * Whether the author was told their content was restricted.
	 *
	 * <p>DSA Art. 17 requires a statement of reasons for a restriction, and this is
	 * a restriction. It is also, for this category, a tip-off: telling a suspect
	 * their upload was matched against a hash list tells them the match happened.
	 * The two obligations genuinely conflict and the resolution is not a code
	 * decision — so the row records which way the operator went instead of
	 * silently skipping the notice and leaving no trace that it was ever owed.
	 */
	@Builder.Default
	private boolean statementWithheld = true;

	/** The admin who released it; {@code null} while the freeze stands. */
	private String unfrozenBy;

	private Instant unfrozenAt;

	/**
	 * Why it was released. Mandatory on unfreeze, and kept even though the row
	 * itself is deleted from the active registry: an unfreeze is an administrative
	 * correction that somebody has to be able to defend, and "it was released" with
	 * no reason is not defensible.
	 */
	private String unfreezeNote;

	/** Whether this row still restricts anything. */
	public boolean active() {
		return unfrozenAt == null;
	}
}
