package com.ahmadre.hinata.timetracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.time.LocalDate;
import java.util.List;

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

	// The range reads below are written out rather than derived from a
	// `Between` keyword. Spring Data translates `Between` to $gt/$lt — exclusive
	// at BOTH ends — while every caller means an inclusive range: the timesheet
	// asks from=Monday to=Sunday and the MCP tool documents both days as
	// inclusive, so a derived query silently dropped the first and last day of
	// every week anyone looked at. Spelling the property twice
	// (…DateGreaterThanEqualAndDateLessThanEqual) is not an option either: two
	// criteria on one key throw at query time.

	@Query("{ 'userId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
	List<WorkItem> findByUserIdInDateRange(String userId, LocalDate from, LocalDate to);

	@Query("{ 'date': { $gte: ?0, $lte: ?1 } }")
	List<WorkItem> findInDateRange(LocalDate from, LocalDate to);

	@Query("{ 'projectId': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
	List<WorkItem> findByProjectIdInDateRange(String projectId, LocalDate from, LocalDate to);

	/** Both filters at once — the self-scoped timesheet narrowed to one project. */
	@Query("{ 'userId': ?0, 'projectId': ?1, 'date': { $gte: ?2, $lte: ?3 } }")
	List<WorkItem> findByUserIdAndProjectIdInDateRange(String userId, String projectId,
			LocalDate from, LocalDate to);

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
