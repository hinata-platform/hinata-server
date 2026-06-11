package hn.asta.hivora.issue;

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

	long countByProjectId(String projectId);

	long countByProjectIdAndStateIn(String projectId, List<String> states);
}
