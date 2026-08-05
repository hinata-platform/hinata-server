package com.ahmadre.hinata.moderation;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One recorded verdict — the row the admin moderation queue is built from.
 *
 * <p>A record holds the decision and a <em>pointer</em> to the content, never the
 * content. That rule comes from {@link ModerationRecorder} and it is what makes this
 * collection defensible at all: a second store of the most sensitive text in the
 * product would have to be carried through the data export, the erasure path and
 * every backup, in exchange for nothing the primary record does not already give —
 * the moderator opens the issue or the comment and reads it there.
 *
 * <p>{@link #contentHash} is the one exception, and only for content that was
 * refused: nothing was written anywhere, so without it a refusal is not even
 * countable. A hash is enough to see the same payload being retried and to notice an
 * account tripping the filter repeatedly, and not enough to reconstruct what was
 * written.
 *
 * <p>The verdict half of a record is immutable — it is what the machine decided at
 * write time, under the thresholds in force then, and a later re-tuning must not be
 * able to rewrite it. Only the review fields are ever updated, and only by a human.
 */
@Data
@Builder
@Document("moderation_records")
// The queue is opened on its OPEN filter, newest first, every single time; this
// compound serves that filter+sort as an IXSCAN instead of sorting the collection in
// memory. The _id tiebreaker keeps paging stable when two records share a
// millisecond, which they routinely do — one create writes a title and a body.
@CompoundIndex(name = "review_created", def = "{'reviewState': 1, 'createdAt': -1, '_id': -1}")
public class ModerationRecord {

	/**
	 * Where a record stands in a moderator's workflow.
	 *
	 * <p>Kept separate from {@link #decision} rather than folded into it: the decision
	 * is what the machine did and never changes, the review state is what a human made
	 * of it. Collapsing the two would destroy the only number that says whether the
	 * thresholds are set right — how often a human reversed the machine.
	 */
	public enum ReviewState {

		/** Waiting for a moderator. The queue's default filter. */
		OPEN,

		/** A human agreed with the verdict. */
		CONFIRMED,

		/** A human overruled it — the content was fine. */
		DISMISSED
	}

	/**
	 * One category that fired, with the confidence behind it.
	 *
	 * <p>Every match is kept, not only {@link ModerationVerdict#primary()}: the author
	 * is told one reason, but a moderator judging a borderline item needs to see that
	 * it scored on three axes rather than one, and a threshold can only ever be
	 * re-tuned against the scores that were actually observed.
	 */
	@Data
	@Builder
	public static class CategoryScore {

		private ModerationCategory category;

		/** Confidence 0-100 as the tier reported it, before any threshold was applied. */
		private int score;

		/**
		 * Which rule fired — a lexicon entry, a scanner signature — never a quotation
		 * of the content. Truncated on write by {@link MongoModerationRecorder}, because
		 * this collection must not become a second copy of the text and a future
		 * provider client that puts a snippet here would quietly make it one.
		 */
		private String evidence;
	}

	@Id
	private String id;

	private ModerationSurface surface;

	private ModerationDecision decision;

	/**
	 * Which stage produced the verdict. Recorded because a statement of reasons has to
	 * say whether automated means were used (DSA Art. 17(3)(c)), and because a
	 * reviewer judges a score differently depending on whether it came from the
	 * built-in lexicon or from a provider that was swapped out last month.
	 */
	private ModerationVerdict.ModerationTier tier;

	/**
	 * Whether a tier was unavailable when this ran. A degraded record is queued even
	 * when nothing fired, which is the whole point of the fail-open path: the bypass
	 * becomes a row someone can count rather than a log line nobody reads.
	 */
	private boolean degraded;

	private List<CategoryScore> categories;

	/** Entity kind the verdict was about — {@code issue}, {@code comment}, {@code attachment}. */
	private String targetType;

	/** The entity's id, or null when the write was refused and no entity exists. */
	private String targetId;

	/** Owning project when there is one, so the queue can be scoped to one team's work. */
	private String projectId;

	/**
	 * Who wrote it, or null for externally authored ingress. Indexed on its own because
	 * the first question any queue row raises is whether this account has been here
	 * before, and that lookup must not scan the collection.
	 */
	@Indexed
	private String authorId;

	/** Short human handle for the queue row (an issue key, a file name) — never the content. */
	private String label;

	/**
	 * Lowercase hex SHA-256 of the content, set <em>only</em> when the content was
	 * refused and therefore exists nowhere else. Null on every other record: where the
	 * content was stored, {@link #targetId} points at it and a hash would add nothing
	 * but a second identifier to keep in sync.
	 */
	private String contentHash;

	@CreatedDate
	private Instant createdAt;

	private ReviewState reviewState;

	/** The moderator who reviewed it — an accountable name behind an enforcement action. */
	private String reviewedBy;

	private Instant reviewedAt;

	/**
	 * Why the moderator decided as they did. The human counterpart to
	 * {@link CategoryScore#getEvidence()}, and what an appeal is answered from months
	 * later, when nobody remembers the item.
	 */
	private String reviewNote;
}
