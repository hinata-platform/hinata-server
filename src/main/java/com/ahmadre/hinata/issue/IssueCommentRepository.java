package com.ahmadre.hinata.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IssueCommentRepository extends MongoRepository<IssueComment, String> {

	List<IssueComment> findByIssueIdOrderByCreatedAtAsc(String issueId);

	/** Newest-first page of a thread, for the paginated comments endpoint. */
	Page<IssueComment> findByIssueIdOrderByCreatedAtDesc(String issueId, Pageable pageable);

	/** Comments a user authored — for the GDPR self-service data export. */
	List<IssueComment> findByAuthorIdOrderByCreatedAtDesc(String authorId);

	void deleteByIssueId(String issueId);
}
