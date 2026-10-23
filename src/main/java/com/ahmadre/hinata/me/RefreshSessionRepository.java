package com.ahmadre.hinata.me;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RefreshSessionRepository extends MongoRepository<RefreshSession, String> {

	List<RefreshSession> findByUserIdOrderByLastActiveAtDesc(String userId);

	Page<RefreshSession> findByUserIdOrderByLastActiveAtDesc(String userId, Pageable pageable);

	void deleteByUserId(String userId);

	void deleteByUserIdAndIdNot(String userId, String id);

	/** The sessions opened before [cutoff], or at a time nobody recorded: id and owner only. */
	@Query(value = "{ 'createdAt': { $not: { $gte: ?0 } } }", fields = "{ 'userId': 1 }")
	List<RefreshSession> findOpenedBefore(Instant cutoff);

	/** As {@link #findOpenedBefore(Instant)}, of the people in [userIds] only. */
	@Query(value = "{ 'userId': { $in: ?0 }, 'createdAt': { $not: { $gte: ?1 } } }", fields = "{ 'userId': 1 }")
	List<RefreshSession> findOpenedBefore(Collection<String> userIds, Instant cutoff);

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
