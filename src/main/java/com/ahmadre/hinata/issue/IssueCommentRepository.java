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

	/** One page of a root comment's replies; order (oldest-first) comes from the Pageable. */
	Page<IssueComment> findByReplyToId(String replyToId, Pageable pageable);

	/** All replies of a root comment (unpaged) — used to cascade-delete + free voice blobs. */
	List<IssueComment> findByReplyToId(String replyToId);

	/** Reply count for a single root comment. */
	long countByReplyToId(String replyToId);

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
