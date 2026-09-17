package com.ahmadre.hinata.timeoff;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TimeOffEmploymentRepository extends MongoRepository<TimeOffEmployment, String> {

	Optional<TimeOffEmployment> findByUserId(String userId);

	/**
	 * Removed with the account: joining and leaving dates exist to compute entitlements, and once
	 * the person is gone there is nothing left to compute (Art. 5 Abs. 1 lit. e DSGVO).
	 */
	long deleteByUserId(String userId);
}
