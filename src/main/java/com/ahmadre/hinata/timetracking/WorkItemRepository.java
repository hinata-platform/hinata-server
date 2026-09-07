package com.ahmadre.hinata.timetracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface WorkItemRepository extends MongoRepository<WorkItem, String> {

	/**
	 * The order every per-issue listing uses: newest day first, and within a day
	 * the most recently created entry first. The {@code _id} tiebreaker is what
	 * makes paging deterministic — two entries on the same day would otherwise
	 * be free to swap places between one page and the next.
	 */
	Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("_id"));

	/** One page of an issue's entries; pass {@link #NEWEST_FIRST} for the sort. */
	Page<WorkItem> findByIssueId(String issueId, Pageable pageable);

	long countByIssueId(String issueId);

	/**
	 * Cuts an issue's entries loose when the issue is deleted: the issue
	 * reference goes, the project stays, and so do the hours. Nothing is ever
	 * deleted here — that is the whole difference to the {@code deleteByIssueId}
	 * this replaces. Returns how many entries were detached.
	 */
	@Query("{ 'issueId': ?0 }")
	@Update("{ '$unset': { 'issueId': 1 } }")
	long detachFromIssue(String issueId);
}
