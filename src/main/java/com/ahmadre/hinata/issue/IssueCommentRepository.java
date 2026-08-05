package com.ahmadre.hinata.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface IssueCommentRepository extends MongoRepository<IssueComment, String> {

	List<IssueComment> findByIssueIdOrderByCreatedAtAsc(String issueId);

	/**
	 * One page of a thread's TOP-LEVEL comments (replies excluded via
	 * {@code replyToId == null}). The sort direction (newest- vs oldest-first) is
	 * supplied by the {@link Pageable}, so a single method serves both orderings.
	 */
	Page<IssueComment> findByIssueIdAndReplyToIdIsNull(String issueId, Pageable pageable);

	/**
	 * As above, minus the authors the viewer has blocked.
	 *
	 * <p>A separate method rather than always passing an exclusion set, because the
	 * set is empty for almost every account and {@code $nin: []} is a criterion Mongo
	 * still has to carry. The caller picks the plain query when there is nothing to
	 * exclude, so the common read is byte-for-byte the one it always was.
	 *
	 * <p>Excluded in the QUERY and not after the fact: filtering a fetched page would
	 * hand back short pages and a total that counts comments the viewer will never
	 * see, so "load more" would stop early and the counter would lie.
	 */
	Page<IssueComment> findByIssueIdAndReplyToIdIsNullAndAuthorIdNotIn(String issueId,
			Collection<String> authorIds, Pageable pageable);

	/** One page of a root comment's replies; order (oldest-first) comes from the Pageable. */
	Page<IssueComment> findByReplyToId(String replyToId, Pageable pageable);

	/** As above, minus the authors the viewer has blocked. */
	Page<IssueComment> findByReplyToIdAndAuthorIdNotIn(String replyToId,
			Collection<String> authorIds, Pageable pageable);

	/** All replies of a root comment (unpaged) — used to cascade-delete + free voice blobs. */
	List<IssueComment> findByReplyToId(String replyToId);

	/** Reply count for a single root comment. */
	long countByReplyToId(String replyToId);

	/** As above, ignoring replies by the authors the viewer has blocked. */
	long countByReplyToIdAndAuthorIdNotIn(String replyToId, Collection<String> authorIds);

	/**
	 * Batched reply counts for a page of root comments: one grouped aggregation
	 * instead of N per-comment counts. Roots with zero replies are simply absent
	 * from the result (the caller defaults them to 0).
	 */
	@Aggregation(pipeline = {
		"{ $match: { replyToId: { $in: ?0 } } }",
		"{ $group: { _id: '$replyToId', count: { $sum: 1 } } }",
		"{ $project: { _id: 0, rootId: '$_id', count: 1 } }"
	})
	List<ReplyCount> countRepliesGrouped(Collection<String> rootIds);

	/**
	 * As {@link #countRepliesGrouped}, ignoring replies by the authors the viewer has
	 * blocked.
	 *
	 * <p>The badge and the list it opens have to be counted by the same rule. Hiding
	 * a blocked author's reply while still counting it produces a thread that
	 * promises three replies and shows two — the exact "hole in the thread" that
	 * makes a viewer think the product lost something.
	 */
	@Aggregation(pipeline = {
		"{ $match: { replyToId: { $in: ?0 }, authorId: { $nin: ?1 } } }",
		"{ $group: { _id: '$replyToId', count: { $sum: 1 } } }",
		"{ $project: { _id: 0, rootId: '$_id', count: 1 } }"
	})
	List<ReplyCount> countRepliesGroupedExcludingAuthors(Collection<String> rootIds,
			Collection<String> authorIds);

	/** Projection for {@link #countRepliesGrouped}. */
	record ReplyCount(String rootId, long count) {}

	/** Pinned comments of a thread, in pin order — surfaced above the feed. */
	List<IssueComment> findByIssueIdAndPinnedIsTrueOrderByPinnedAtAsc(String issueId);

	/** Comments a user authored — for the GDPR self-service data export. */
	List<IssueComment> findByAuthorIdOrderByCreatedAtDesc(String authorId);

	/** Cascade: delete every reply under a root comment. */
	void deleteByReplyToId(String replyToId);

	void deleteByIssueId(String issueId);
}
