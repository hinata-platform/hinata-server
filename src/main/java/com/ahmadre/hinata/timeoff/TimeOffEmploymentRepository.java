package com.ahmadre.hinata.timeoff;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TimeOffEmploymentRepository extends MongoRepository<TimeOffEmployment, String> {

	Optional<TimeOffEmployment> findByUserId(String userId);

	/** The dates for a page of people at once, for a keeper's list. */
	List<TimeOffEmployment> findByUserIdIn(Collection<String> userIds);

	/**
	 * Removed with the account: joining and leaving dates exist to compute entitlements, and once
	 * the person is gone there is nothing left to compute (Art. 5 Abs. 1 lit. e DSGVO).
	 */
	long deleteByUserId(String userId);
}
