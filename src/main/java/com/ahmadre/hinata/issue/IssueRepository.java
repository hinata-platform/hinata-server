package com.ahmadre.hinata.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends MongoRepository<Issue, String> {

	Optional<Issue> findByReadableIdIgnoreCase(String readableId);

	Page<Issue> findByProjectId(String projectId, Pageable pageable);

	List<Issue> findByProjectIdAndSprintId(String projectId, String sprintId);

	List<Issue> findBySprintId(String sprintId);

	List<Issue> findByProjectIdAndStartDateNotNull(String projectId);

	List<Issue> findByParentId(String parentId);

	/** Highest issue number currently used in a project — used to repair a
	 * project's issueCounter if it ever falls behind the real data. */
	Optional<Issue> findTopByProjectIdOrderByNumberInProjectDesc(String projectId);

	long countByProjectId(String projectId);

	long countByProjectIdAndStateIn(String projectId, List<String> states);
}
