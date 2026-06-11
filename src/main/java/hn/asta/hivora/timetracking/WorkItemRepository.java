package hn.asta.hivora.timetracking;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;

public interface WorkItemRepository extends MongoRepository<WorkItem, String> {

	List<WorkItem> findByIssueIdOrderByDateDesc(String issueId);

	List<WorkItem> findByUserIdAndDateBetween(String userId, LocalDate from, LocalDate to);

	List<WorkItem> findByDateBetween(LocalDate from, LocalDate to);

	List<WorkItem> findByProjectIdAndDateBetween(String projectId, LocalDate from, LocalDate to);

	void deleteByIssueId(String issueId);
}
