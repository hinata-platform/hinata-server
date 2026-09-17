package com.ahmadre.hinata.timeoff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TimeOffLedgerRepository extends MongoRepository<TimeOffLedgerEntry, String> {

	/** One person's movements for one type and year, in the order the index serves. */
	Page<TimeOffLedgerEntry> findByUserIdAndTypeIdAndYear(String userId, String typeId, Integer year,
			Pageable pageable);

	/** Whether a type has any history: what stands between deleting one and switching it off. */
	boolean existsByTypeId(String typeId);

	// No deleteByUserId, and that is the decision rather than an omission: the journal is the
	// record of leave granted and taken, which outlives the account the way a work item does.
	// It keeps the person's id as a pseudonym after the account is gone (UserService.delete's
	// convention), and the retention policy in A4 decides when it goes. What the erasure does
	// remove is TimeOffEmployment: joining and leaving dates exist only to compute entitlements,
	// and there is nothing left to compute.
}
