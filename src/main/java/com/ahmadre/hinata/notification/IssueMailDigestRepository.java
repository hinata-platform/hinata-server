package com.ahmadre.hinata.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IssueMailDigestRepository extends MongoRepository<IssueMailDigest, String> {

	/** The recipient's pending bundle for one issue — at most one exists (unique
	 *  partial index), so this doubles as the "is anything queued" check. */
	List<IssueMailDigest> findByUserIdAndIssueIdAndSentAtIsNull(String userId, String issueId);
}
