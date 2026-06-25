package com.ahmadre.hinata.issue;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IssueCommentRepository extends MongoRepository<IssueComment, String> {

	List<IssueComment> findByIssueIdOrderByCreatedAtAsc(String issueId);

	/** Comments a user authored — for the GDPR self-service data export. */
	List<IssueComment> findByAuthorIdOrderByCreatedAtDesc(String authorId);

	void deleteByIssueId(String issueId);
}
