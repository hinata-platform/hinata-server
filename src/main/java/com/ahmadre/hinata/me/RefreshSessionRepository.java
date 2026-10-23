package com.ahmadre.hinata.me;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RefreshSessionRepository extends MongoRepository<RefreshSession, String> {

	List<RefreshSession> findByUserIdOrderByLastActiveAtDesc(String userId);

	Page<RefreshSession> findByUserIdOrderByLastActiveAtDesc(String userId, Pageable pageable);

	void deleteByUserId(String userId);

	void deleteByUserIdAndIdNot(String userId, String id);

	/**
	 * How many sessions each person has and when the latest was last active, for every person in
	 * one pass: the admin user list sorts and shows both for everybody, and asking per person
	 * cost two reads per comparison of the sort. Walks {@code user_lastActive} in its own order,
	 * so the first of each group is the latest.
	 */
	@Aggregation(pipeline = {
			"{ $sort: { userId: 1, lastActiveAt: -1 } }",
			"{ $group: { _id: '$userId', sessions: { $sum: 1 }, lastActive: { $first: '$lastActiveAt' } } }"
	})
	List<SessionService.Activity> activityByUser();
}
