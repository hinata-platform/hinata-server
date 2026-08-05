package com.ahmadre.hinata.moderation.freeze;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link FrozenContent}.
 *
 * <p>Read almost exclusively as a whole. {@link #findByUnfrozenAtIsNull()} is the
 * query that matters: {@link FrozenContentService} holds the active set in
 * memory, because a freeze has to be consulted on every read of every entity and
 * every byte in the product, and a database round trip per read is not a cost the
 * product can carry to answer a question whose answer is "no" in every install
 * that has never had an incident.
 */
public interface FrozenContentRepository extends MongoRepository<FrozenContent, String> {

	/** Every freeze still in force — the whole snapshot, refreshed as one. */
	List<FrozenContent> findByUnfrozenAtIsNull();

	/**
	 * The single row for one target, frozen or already released.
	 *
	 * <p>Released rows are matched too, on purpose: freezing something that was
	 * unfrozen last month must reuse the row rather than collide with it under the
	 * unique index, and the history of both decisions is what an appeal is answered
	 * from.
	 */
	Optional<FrozenContent> findByTargetTypeAndTargetId(FrozenTargetType targetType, String targetId);

	/** Everything frozen on one report — what an unfreeze of that report releases. */
	List<FrozenContent> findByReportId(String reportId);
}
