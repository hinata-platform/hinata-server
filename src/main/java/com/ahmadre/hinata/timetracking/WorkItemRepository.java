package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface WorkItemRepository extends MongoRepository<WorkItem, String> {

	List<WorkItem> findByIssueIdOrderByDateDesc(String issueId);

	// The three range reads below are written out rather than derived from a
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

	void deleteByIssueId(String issueId);
}
