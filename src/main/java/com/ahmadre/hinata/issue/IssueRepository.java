package com.ahmadre.hinata.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends MongoRepository<Issue, String> {

	Optional<Issue> findByReadableIdIgnoreCase(String readableId);

	/** The issue that once carried this readable id before being moved to another
	 * project — lets an old key (in a commit message, an e-mail, a chat link)
	 * still resolve. Only consulted after the current-id lookup misses. Readable
	 * ids are always stored upper-cased, so callers must normalise first. */
	Optional<Issue> findByFormerReadableIdsContains(String readableId);

	/** The ticket created from a given inbound e-mail — used to reprocess a mailbox
	 * without creating duplicates. */
	Optional<Issue> findByInboundMessageId(String inboundMessageId);

	/** Issues a user reported — for the GDPR self-service data export. */
	List<Issue> findByReporterIdOrderByCreatedAtDesc(String reporterId);

	/** Issues currently assigned to a user (primary or secondary) — for the GDPR data export. */
	List<Issue> findByAssigneeIdsContainsOrderByCreatedAtDesc(String assigneeId);

	Page<Issue> findByProjectId(String projectId, Pageable pageable);

	List<Issue> findByProjectIdAndSprintId(String projectId, String sprintId);

	List<Issue> findBySprintId(String sprintId);

	List<Issue> findByProjectIdAndStartDateNotNull(String projectId);

	/** Everything that belongs on the timeline: non-archived issues carrying a start
	 * date, a due date, or both. A due-date-only issue is a deadline/milestone and
	 * must not be dropped, which is why this replaces the start-date-only query. */
	@Query("{ 'projectId': ?0, 'archived': { $ne: true }, "
			+ "$or: [ { 'startDate': { $ne: null } }, { 'dueDate': { $ne: null } } ] }")
	List<Issue> findScheduled(String projectId);


	List<Issue> findByParentId(String parentId);

	/** Direct children of a batch of parents — one index-backed ({@code parent_number})
	 * query to compute sub-task counts for a whole board or issue-list page. */
	List<Issue> findByParentIdIn(List<String> parentIds);

	/** Highest issue number currently used in a project — used to repair a
	 * project's issueCounter if it ever falls behind the real data. */
	Optional<Issue> findTopByProjectIdOrderByNumberInProjectDesc(String projectId);

	/** Whether a number is already taken in a project — the check that keeps a
	 * move from walking into the unique {@code project_number} index. Sees writes
	 * made earlier in the same transaction, which a counter cannot. */
	boolean existsByProjectIdAndNumberInProject(String projectId, long numberInProject);

	long countByProjectId(String projectId);

	long countByProjectIdAndStateIn(String projectId, List<String> states);
}
