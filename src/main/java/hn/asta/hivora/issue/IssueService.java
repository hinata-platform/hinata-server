package hn.asta.hivora.issue;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.notification.NotificationService;
import hn.asta.hivora.project.Project;
import hn.asta.hivora.project.ProjectService;
import hn.asta.hivora.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IssueService {

	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final MongoTemplate mongo;

	public Issue get(String idOrReadableId) {
		return issues.findById(idOrReadableId)
				.or(() -> issues.findByReadableIdIgnoreCase(idOrReadableId))
				.orElseThrow(() -> ApiException.notFound("Issue"));
	}

	public Issue create(Issue issue, User author) {
		Project project = projects.get(issue.getProjectId());
		long number = projects.nextIssueNumber(project.getId());
		issue.setNumberInProject(number);
		issue.setReadableId(project.getKey() + "-" + number);
		if (issue.getState() == null || !project.getWorkflowStates().contains(issue.getState())) {
			issue.setState(project.getWorkflowStates().get(0));
		}
		if (author != null) {
			issue.setReporterId(author.getId());
		}
		issue.setRank(Instant.now().toEpochMilli());
		Issue saved = issues.save(issue);
		if (saved.getAssigneeId() != null && (author == null || !saved.getAssigneeId().equals(author.getId()))) {
			notifications.notifyIssueAssigned(saved);
		}
		return saved;
	}

	public Issue update(String id, java.util.function.Consumer<Issue> mutator, User editor) {
		Issue issue = get(id);
		String previousAssignee = issue.getAssigneeId();
		String previousState = issue.getState();
		mutator.accept(issue);

		Project project = projects.get(issue.getProjectId());
		if (!project.getWorkflowStates().contains(issue.getState())) {
			throw ApiException.badRequest("Unknown workflow state: " + issue.getState());
		}
		boolean nowResolved = project.getResolvedStates().contains(issue.getState());
		issue.setResolvedAt(nowResolved
				? (issue.getResolvedAt() != null ? issue.getResolvedAt() : Instant.now())
				: null);

		Issue saved = issues.save(issue);
		if (saved.getAssigneeId() != null && !saved.getAssigneeId().equals(previousAssignee)) {
			notifications.notifyIssueAssigned(saved);
		}
		else if (!saved.getState().equals(previousState)) {
			notifications.notifyIssueUpdated(saved, editor,
					"State changed to \"" + saved.getState() + "\"");
		}
		return saved;
	}

	public void delete(String id) {
		Issue issue = get(id);
		comments.deleteByIssueId(issue.getId());
		issues.delete(issue);
	}

	/** Filtered, paginated search. Free-text is regex-escaped (NoSQL injection safe). */
	public Page<Issue> search(String projectId, String state, String assigneeId, String sprintId,
			String type, String text, int page, int size) {
		Query query = new Query();
		if (projectId != null) query.addCriteria(Criteria.where("projectId").is(projectId));
		if (state != null) query.addCriteria(Criteria.where("state").is(state));
		if (assigneeId != null) query.addCriteria(Criteria.where("assigneeId").is(assigneeId));
		if (sprintId != null) query.addCriteria(Criteria.where("sprintId").is(sprintId));
		if (type != null) query.addCriteria(Criteria.where("type").is(type));
		if (text != null && !text.isBlank()) {
			String quoted = Pattern.quote(text.trim());
			query.addCriteria(new Criteria().orOperator(
					Criteria.where("title").regex(quoted, "i"),
					Criteria.where("readableId").regex("^" + quoted, "i")));
		}
		Pageable pageable = PageRequest.of(page, Math.min(size, 100),
				Sort.by(Sort.Direction.DESC, "updatedAt"));
		long total = mongo.count(query, Issue.class);
		List<Issue> content = mongo.find(query.with(pageable), Issue.class);
		return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
	}

	public IssueComment addComment(String issueId, String text, User author) {
		Issue issue = get(issueId);
		IssueComment comment = IssueComment.builder()
				.issueId(issue.getId())
				.authorId(author.getId())
				.text(text)
				.build();
		IssueComment saved = comments.save(comment);
		notifications.notifyIssueCommented(issue, author);
		return saved;
	}

	public List<IssueComment> commentsOf(String issueId) {
		return comments.findByIssueIdOrderByCreatedAtAsc(get(issueId).getId());
	}
}
