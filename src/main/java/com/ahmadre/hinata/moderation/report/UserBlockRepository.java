package com.ahmadre.hinata.moderation.report;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserBlockRepository extends MongoRepository<UserBlock, String> {

	/**
	 * Every block one user holds, newest first. Read by
	 * {@link UserBlockService#blockedBy(String)} on the way into comment and mention
	 * reads, so it must stay an index-covered lookup on a single equality prefix —
	 * never widened with a second criterion that would drop it off
	 * {@code blocker_blocked}.
	 */
	List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(String blockerId);

	/**
	 * Every block pointing AT one user — the inverse direction, read by
	 * {@link UserBlockService#blockersOf(String)} before a notification fan-out.
	 *
	 * <p>The read side asks "whose writing do I hide?" and knows the viewer; the
	 * fan-out side asks "who does this author's message not reach?" and knows only
	 * the author. Answering the second question with the first query would mean one
	 * lookup per recipient inside the fan-out loop, so it gets its own index.
	 */
	List<UserBlock> findByBlockedId(String blockedId);

	Optional<UserBlock> findByBlockerIdAndBlockedId(String blockerId, String blockedId);

	void deleteByBlockerIdAndBlockedId(String blockerId, String blockedId);

	/**
	 * Removes both directions for a user who is being deleted. A block pointing at an
	 * account that no longer exists is dead weight that would otherwise outlive the
	 * erasure request that removed it.
	 */
	void deleteByBlockerIdOrBlockedId(String blockerId, String blockedId);
}
