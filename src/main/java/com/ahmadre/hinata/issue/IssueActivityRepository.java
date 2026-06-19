package com.ahmadre.hinata.issue;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IssueActivityRepository extends MongoRepository<IssueActivity, String> {

	List<IssueActivity> findByIssueIdOrderByCreatedAtDesc(String issueId);

	void deleteByIssueId(String issueId);
}
