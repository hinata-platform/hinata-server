package com.ahmadre.hinata.timetracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TimesheetApprovalRepository extends MongoRepository<TimesheetApproval, String> {

	/** Newest submission first, {@code _id} as the tiebreaker so paging is stable. */
	Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("_id"));

	/** The one row for this person, project and period start — the unique key. */
	Optional<TimesheetApproval> findByUserIdAndProjectIdAndPeriodStart(String userId,
			String projectId, LocalDate periodStart);

	/**
	 * Every submission of this person for this project whose period could contain
	 * a given day — those that end on or after it.
	 *
	 * <p>Walks the {@code user_project_end} index and hands back the handful of
	 * rows a person has for one project, which the caller then filters on
	 * {@code periodStart} and status. Deliberately not a four-field query: Mongo
	 * cannot use a second range bound in an index, so narrowing
	 * {@code periodStart} here would cost an in-memory filter over the same rows
	 * and read as if it were free.
	 */
	List<TimesheetApproval> findByUserIdAndProjectIdAndPeriodEndGreaterThanEqual(String userId,
			String projectId, LocalDate periodEnd);

	/** This person's submissions for these projects overlapping a window — the timesheet's status row. */
	List<TimesheetApproval> findByUserIdAndProjectIdInAndPeriodEndGreaterThanEqualAndPeriodStartLessThanEqual(
			String userId, Collection<String> projectIds, LocalDate from, LocalDate to);

	/** This person's own submissions, newest first. */
	Page<TimesheetApproval> findByUserId(String userId, Pageable pageable);

	Page<TimesheetApproval> findByUserIdAndStatus(String userId, TimesheetApproval.Status status,
			Pageable pageable);

	/** Every submission in one state — the administrator's inbox, which has no project filter. */
	Page<TimesheetApproval> findByStatus(TimesheetApproval.Status status, Pageable pageable);

	/** The approver's inbox: the projects they lead. */
	Page<TimesheetApproval> findByProjectIdIn(Collection<String> projectIds, Pageable pageable);

	Page<TimesheetApproval> findByProjectIdInAndStatus(Collection<String> projectIds,
			TimesheetApproval.Status status, Pageable pageable);

	/**
	 * A deleted account's submissions go with it — see {@code TimeTrackingErasure}.
	 *
	 * <p>A deleted <em>project</em>'s go too, but through {@code DeletionService},
	 * which removes them beside every other collection the cascade touches; that is
	 * one story in one place, and it runs whether or not the module is switched on.
	 */
	long deleteByUserId(String userId);

	/**
	 * Whether somebody else has already claimed an overlapping span for this
	 * person and project.
	 *
	 * <p>The after-the-fact half of the concurrency check — see
	 * {@code TimesheetApprovalService.submit}. Mongo cannot express "no two rows
	 * overlap" as an index, so the service inserts and then asks this.
	 */
	List<TimesheetApproval> findByUserIdAndProjectIdAndStatusInAndPeriodEndGreaterThanEqual(
			String userId, String projectId, Collection<TimesheetApproval.Status> statuses,
			LocalDate periodEnd);
}
