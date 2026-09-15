package com.ahmadre.hinata.issue;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface IssueLinkRepository extends MongoRepository<IssueLink, String> {

	/** Every link touching an issue, on either end. */
	List<IssueLink> findBySourceIdOrTargetId(String sourceId, String targetId);

	Optional<IssueLink> findByTypeAndSourceIdAndTargetId(IssueLinkType type, String sourceId,
			String targetId);
}
