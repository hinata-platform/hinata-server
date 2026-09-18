package com.ahmadre.hinata.timeoff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** Requests, read the four ways anybody reads them. */
public interface TimeOffRequestRepository extends MongoRepository<TimeOffRequest, String> {

	/**
	 * Newest first, and by id where two start on the same day.
	 *
	 * <p>The tiebreaker is not decoration: without it two requests for the same Monday can swap
	 * places between one page and the next, so somebody paging through their own history sees one
	 * twice and another never.
	 */
	Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("from"), Sort.Order.desc("_id"));

	Page<TimeOffRequest> findByUserId(String userId, Pageable pageable);

	Page<TimeOffRequest> findByUserIdAndStatus(String userId, TimeOffRequest.Status status, Pageable pageable);

	Page<TimeOffRequest> findByApproverIdsContains(String approverId, Pageable pageable);

	Page<TimeOffRequest> findByApproverIdsContainsAndStatus(String approverId, TimeOffRequest.Status status,
			Pageable pageable);

	/** Who else is away across [from]–[to], for the clash line a decider sees. */
	List<TimeOffRequest> findByStatusInAndToGreaterThanEqualAndFromLessThanEqual(
			Collection<TimeOffRequest.Status> statuses, LocalDate from, LocalDate to, Pageable pageable);

	/** Approved requests of one person touching a span — § 9 BUrlG asks this of a sick note. */
	List<TimeOffRequest> findByUserIdAndStatusAndToGreaterThanEqualAndFromLessThanEqual(String userId,
			TimeOffRequest.Status status, LocalDate from, LocalDate to);

	boolean existsByTypeId(String typeId);

	/**
	 * Drops the requests of a deleted account that nobody decided.
	 *
	 * <p>Decided ones stay and are pseudonymised instead — an approved absence is a business
	 * record of who was granted what, the same reasoning that keeps approved timesheets.
	 */
	long deleteByUserIdAndStatusIn(String userId, Collection<TimeOffRequest.Status> statuses);

	List<TimeOffRequest> findByUserId(String userId);
}
