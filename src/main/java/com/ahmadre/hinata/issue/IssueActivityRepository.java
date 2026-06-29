package com.ahmadre.hinata.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IssueActivityRepository extends MongoRepository<IssueActivity, String> {

	List<IssueActivity> findByIssueIdOrderByCreatedAtDesc(String issueId);

	/** Newest-first page of the change history, for the paginated activity endpoint. */
	Page<IssueActivity> findByIssueIdOrderByCreatedAtDesc(String issueId, Pageable pageable);

	void deleteByIssueId(String issueId);
}
