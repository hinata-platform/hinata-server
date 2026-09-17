package com.ahmadre.hinata.timeoff;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TimeOffEntitlementRepository extends MongoRepository<TimeOffEntitlement, String> {

	Optional<TimeOffEntitlement> findByUserIdAndTypeIdAndYear(String userId, String typeId,
			Integer year);

	/** One person's grants for a year, across every type: what a balance screen is built from. */
	List<TimeOffEntitlement> findByUserIdAndYear(String userId, Integer year);

	/** Whether a type has been granted to anybody: half of what keeps it from being deleted. */
	boolean existsByTypeId(String typeId);

	// Like the journal, grants stay when an account goes: they are what was promised, and the
	// evidence an employer needs for § 2 Abs. 1 S. 2 Nr. 8 NachwG. A4's retention decides when.
}
