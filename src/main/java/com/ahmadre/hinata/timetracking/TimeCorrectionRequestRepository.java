package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface TimeCorrectionRequestRepository extends MongoRepository<TimeCorrectionRequest, String> {

	/** A deleted account's requests, answers included — see {@code TimeTrackingErasure}. */
	long deleteByUserId(String userId);
}
