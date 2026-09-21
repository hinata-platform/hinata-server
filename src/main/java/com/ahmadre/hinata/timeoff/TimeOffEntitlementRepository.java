package com.ahmadre.hinata.timeoff;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TimeOffEntitlementRepository extends MongoRepository<TimeOffEntitlement, String> {

	Optional<TimeOffEntitlement> findByUserIdAndTypeIdAndYear(String userId, String typeId,
			Integer year);

	/** One person's grants for a year, across every type: what a balance screen is built from. */
	List<TimeOffEntitlement> findByUserIdAndYear(String userId, Integer year);

	/**
	 * One type and year for a page of people, in one query.
	 *
	 * <p>A keeper's list shows a page of the directory beside what each person was granted. Asking
	 * per row would cost a round trip per name, which is the shape that turns a screen of
	 * twenty-five into twenty-five queries.
	 */
	List<TimeOffEntitlement> findByTypeIdAndYearAndUserIdIn(String typeId, Integer year,
			Collection<String> userIds);

	/** A type's grants for a year, a page at a time: the monthly accrual walks them (HIN-119). */
	org.springframework.data.domain.Page<TimeOffEntitlement> findByTypeIdAndYear(String typeId, Integer year,
			org.springframework.data.domain.Pageable pageable);

	/** Whether a type has been granted to anybody: half of what keeps it from being deleted. */
	boolean existsByTypeId(String typeId);

	// Like the journal, grants stay when an account goes: they are what was promised, and the
	// evidence an employer needs for § 2 Abs. 1 S. 2 Nr. 8 NachwG. A4's retention decides when.
}
