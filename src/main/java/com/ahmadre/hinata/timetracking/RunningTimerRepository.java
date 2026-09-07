package com.ahmadre.hinata.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunningTimerRepository extends MongoRepository<RunningTimer, String> {

	Optional<RunningTimer> findByUserId(String userId);

	/**
	 * Timers that started at or before {@code cutoff} — the sweep's candidates.
	 * Unbounded on purpose: there is at most one timer per person, and a query
	 * that quietly dropped some of them would leave those people's timers running
	 * forever. If an instance ever has more expired timers than it can hold in a
	 * list, the list is not the problem.
	 */
	List<RunningTimer> findByStartedAtLessThanEqual(Instant cutoff);

	/**
	 * Clears a user's timer wholesale — account deletion, and the belt to the
	 * unique index's braces when a stopped timer is removed by owner rather than
	 * by id.
	 */
	long deleteByUserId(String userId);
}
