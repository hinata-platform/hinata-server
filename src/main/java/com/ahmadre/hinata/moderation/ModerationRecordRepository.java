package com.ahmadre.hinata.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;

/**
 * Persistence for {@link ModerationRecord} — written by
 * {@link MongoModerationRecorder}, read by the admin moderation queue.
 */
public interface ModerationRecordRepository extends MongoRepository<ModerationRecord, String> {

	/**
	 * The queue's default read: one review state, newest first. Kept as its own
	 * derived method rather than folded into {@link #search} because this is the query
	 * that runs every time the queue is opened, and it is the one that
	 * {@code review_created} serves end to end.
	 */
	Page<ModerationRecord> findByReviewStateOrderByCreatedAtDesc(ModerationRecord.ReviewState reviewState,
			Pageable pageable);

	/** Size of the backlog — the badge on the admin nav, and the number an alert watches. */
	long countByReviewState(ModerationRecord.ReviewState reviewState);

	/**
	 * How often this account has been recorded since [since].
	 *
	 * <p>The question a single queue row cannot answer: one borderline comment is
	 * noise, the same account appearing eleven times in a week is a pattern, and it is
	 * the only signal the product has for content that was refused and therefore never
	 * stored anywhere a moderator could go back and look at.
	 */
	long countByAuthorIdAndCreatedAtAfter(String authorId, Instant since);

	/**
	 * The filtered queue read. Every parameter is optional — null means "do not filter
	 * on this" — so one query serves a filter bar that would otherwise need a derived
	 * method per combination.
	 *
	 * <p>The optional clauses are expressed with {@code $expr} because a plain
	 * {@code {'field': ?0}} with a null argument means "field is null", which is a
	 * filter of its own and not the absence of one. The cost is that this query cannot
	 * use {@code review_created}: it scans. That is a deliberate trade — the unfiltered
	 * default read goes through {@link #findByReviewStateOrderByCreatedAtDesc} and stays
	 * indexed, this method is only reached once a moderator narrows the list, and the
	 * collection it scans is small by construction because a clean verdict is never
	 * recorded at all.
	 *
	 * @param category matches when <em>any</em> recorded category is this one, not only
	 *                 the primary — content is routinely both harassing and hateful, and
	 *                 a moderator filtering for hate speech wants both
	 */
	@Query("""
			{ $and: [
				{ $expr: { $or: [ { $eq: [?0, null] }, { $eq: ['$reviewState', ?0] } ] } },
				{ $expr: { $or: [ { $eq: [?1, null] }, { $eq: ['$surface', ?1] } ] } },
				{ $expr: { $or: [ { $eq: [?2, null] },
					{ $in: [?2, { $ifNull: ['$categories.category', []] }] } ] } },
				{ $expr: { $or: [ { $eq: [?3, null] }, { $eq: ['$projectId', ?3] } ] } },
				{ $expr: { $or: [ { $eq: [?4, null] }, { $eq: ['$authorId', ?4] } ] } },
				{ $expr: { $or: [ { $eq: [?5, null] }, { $gte: ['$createdAt', ?5] } ] } },
				{ $expr: { $or: [ { $eq: [?6, null] }, { $lte: ['$createdAt', ?6] } ] } }
			] }
			""")
	Page<ModerationRecord> search(ModerationRecord.ReviewState reviewState,
			ModerationSurface surface,
			ModerationCategory category,
			String projectId,
			String authorId,
			Instant from,
			Instant to,
			Pageable pageable);
}
