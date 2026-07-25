package com.ahmadre.hinata.issue;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueLinkRepository extends MongoRepository<IssueLink, String> {

	/** Every link touching an issue, on either end. */
	List<IssueLink> findBySourceIdOrTargetId(String sourceId, String targetId);

	/** Every link touching any issue of a batch, on either end — the timeline
	 * loads a whole project's graph in one round trip instead of per issue. */
	List<IssueLink> findBySourceIdInOrTargetIdIn(Collection<String> sourceIds,
			Collection<String> targetIds);

	Optional<IssueLink> findByTypeAndSourceIdAndTargetId(IssueLinkType type, String sourceId,
			String targetId);
}
