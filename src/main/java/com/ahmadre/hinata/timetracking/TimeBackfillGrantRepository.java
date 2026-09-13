package com.ahmadre.hinata.timetracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface TimeBackfillGrantRepository extends MongoRepository<TimeBackfillGrant, String> {

	/** Whether a grant of this person's that is still running holds {@code day}. */
	boolean existsByUserIdAndFromLessThanEqualAndToGreaterThanEqualAndExpiresAtAfter(String userId,
			LocalDate day, LocalDate sameDay, Instant now);

	/** A person's grants that are still running. */
	List<TimeBackfillGrant> findByUserIdAndExpiresAtAfter(String userId, Instant now);

	/** Every grant still running, for the administrators. */
	Page<TimeBackfillGrant> findByExpiresAtAfter(Instant now, Pageable pageable);

	/** A deleted account's grants — see {@code TimeTrackingErasure}. */
	long deleteByUserId(String userId);
}
