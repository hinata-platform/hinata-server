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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IssueService {

	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final IssueActivityRepository activities;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final MongoTemplate mongo;

	/** Internal lookup with no authorization check. */
	public Issue get(String idOrReadableId) {
		return issues.findById(idOrReadableId)
				.or(() -> issues.findByReadableIdIgnoreCase(idOrReadableId))
				.orElseThrow(() -> ApiException.notFound("issue"));
	}

	/** Lookup that enforces the caller is a member of the issue's project (A01). */
	public Issue getForUser(String idOrReadableId, User user) {
		Issue issue = get(idOrReadableId);
		assertAccess(issue, user);
		return issue;
	}

	/** Throws 403 unless {@code user} is an admin or a member of the project. */
	private void assertAccess(Issue issue, User user) {
		projects.assertMember(projects.get(issue.getProjectId()), user);
	}

	public Issue create(Issue issue, User author) {
		Project project = projects.get(issue.getProjectId());
		if (author != null) {
			projects.assertMember(project, author); // only project members may add issues (A01)
		}
		assignIssueNumber(issue, project);
		if (issue.getState() == null || !project.getWorkflowStates().contains(issue.getState())) {
			issue.setState(project.getWorkflowStates().get(0));
		}
		if (author != null) {
			issue.setReporterId(author.getId());
		}
		issue.setRank(Instant.now().toEpochMilli());
		Issue saved = saveWithNumberRetry(issue, project);
		mergeProjectLabels(project, saved.getTags());
		activities.save(IssueActivity.builder()
				.issueId(saved.getId())
				.actorId(author != null ? author.getId() : null)
				.field(IssueActivity.Field.CREATED)
				.build());
		if (saved.getAssigneeId() != null && (author == null || !saved.getAssigneeId().equals(author.getId()))) {
			notifications.notifyIssueAssigned(saved);
		}
		return saved;
	}

	/** Reserves the next project-scoped number and sets the readable id. */
	private void assignIssueNumber(Issue issue, Project project) {
		long number = projects.nextIssueNumber(project.getId());
		issue.setNumberInProject(number);
		issue.setReadableId(project.getKey() + "-" + number);
	}

	/**
	 * Saves the issue, self-healing a stale project {@code issueCounter}. If the
	 * unique (projectId, numberInProject) index rejects the insert — which means
	 * the counter had fallen behind the real data — the counter is bumped to the
	 * actual maximum and a fresh number is assigned once before giving up.
	 */
	private Issue saveWithNumberRetry(Issue issue, Project project) {
		try {
			return issues.save(issue);
		}
		catch (org.springframework.dao.DuplicateKeyException collision) {
			long maxExisting = issues
					.findTopByProjectIdOrderByNumberInProjectDesc(project.getId())
					.map(Issue::getNumberInProject)
					.orElse(0L);
			projects.ensureIssueCounterAtLeast(project.getId(), maxExisting);
			assignIssueNumber(issue, project);
			return issues.save(issue);
		}
	}

	public Issue update(String id, java.util.function.Consumer<Issue> mutator, User editor) {
		Issue issue = get(id);
		if (editor != null) {
			assertAccess(issue, editor);
		}
		Issue before = snapshot(issue);
		String previousAssignee = issue.getAssigneeId();
		String previousState = issue.getState();
		String previousSprint = issue.getSprintId();
		mutator.accept(issue);

		Project project = projects.get(issue.getProjectId());
		// Pulling a backlog issue into a sprint moves it onto the sprint board:
		// auto-advance it out of the Backlog state so it's actually workable
		// there (the sprint board hides the backlog column).
		if (previousSprint == null && issue.getSprintId() != null
				&& !issue.getSprintId().isBlank()) {
			promoteFromBacklog(issue, project);
		}
		if (!project.getWorkflowStates().contains(issue.getState())) {
			throw ApiException.badRequest("error.issue.unknownState", issue.getState());
		}
		boolean nowResolved = project.getResolvedStates().contains(issue.getState());
		issue.setResolvedAt(nowResolved
				? (issue.getResolvedAt() != null ? issue.getResolvedAt() : Instant.now())
				: null);

		Issue saved = issues.save(issue);
		mergeProjectLabels(project, saved.getTags());
		recordChanges(before, saved, editor);
		if (saved.getAssigneeId() != null && !saved.getAssigneeId().equals(previousAssignee)) {
			notifications.notifyIssueAssigned(saved);
		}
		else if (!saved.getState().equals(previousState)) {
			notifications.notifyIssueUpdated(saved, editor,
					"State changed to \"" + saved.getState() + "\"");
		}
		return saved;
	}

	/** A workflow state counts as "backlog" by its conventional name. */
	private static boolean isBacklogState(String state) {
		return state != null && state.equalsIgnoreCase("backlog");
	}

	/** If [issue] sits in a backlog state, advances it to the first non-backlog
	 * workflow state (e.g. Backlog → Open) so it appears on the sprint board. */
	private void promoteFromBacklog(Issue issue, Project project) {
		if (!isBacklogState(issue.getState())) {
			return;
		}
		for (String state : project.getWorkflowStates()) {
			if (!isBacklogState(state)) {
				issue.setState(state);
				return;
			}
		}
	}

	public void delete(String id, User user) {
		Issue issue = get(id);
		assertAccess(issue, user);
		comments.deleteByIssueId(issue.getId());
		activities.deleteByIssueId(issue.getId());
		issues.delete(issue);
	}

	/** Permanently deletes a label from a project: removes it from the project's
	 * vocabulary and pulls it from every issue in the project that carries it. */
	public void removeProjectLabel(String projectId, String label, User user) {
		Project project = projects.get(projectId);
		if (user != null) projects.assertMember(project, user);
		if (project.getLabels() != null && project.getLabels().remove(label)) {
			projects.save(project);
		}
		mongo.updateMulti(
				new Query(Criteria.where("projectId").is(projectId).and("tags").is(label)),
				new Update().pull("tags", label),
				Issue.class);
	}

	/** Adds any new issue tags to the project's reusable label vocabulary so
	 * they can be suggested when tagging other issues in the same project. */
	private void mergeProjectLabels(Project project, List<String> tags) {
		if (tags == null || tags.isEmpty()) return;
		List<String> labels = project.getLabels();
		if (labels == null) {
			labels = new ArrayList<>();
			project.setLabels(labels);
		}
		boolean changed = false;
		for (String tag : tags) {
			if (tag != null && !tag.isBlank() && !labels.contains(tag)) {
				labels.add(tag);
				changed = true;
			}
		}
		if (changed) projects.save(project);
	}

	public List<IssueActivity> activityOf(String issueId, User user) {
		return activities.findByIssueIdOrderByCreatedAtDesc(getForUser(issueId, user).getId());
	}

	// ── change history ────────────────────────────────────────────────────

	/** A shallow copy of the fields we track for the change history. */
	private Issue snapshot(Issue issue) {
		return Issue.builder()
				.title(issue.getTitle())
				.description(issue.getDescription())
				.type(issue.getType())
				.priority(issue.getPriority())
				.state(issue.getState())
				.assigneeId(issue.getAssigneeId())
				.sprintId(issue.getSprintId())
				.startDate(issue.getStartDate())
				.dueDate(issue.getDueDate())
				.estimateMinutes(issue.getEstimateMinutes())
				.storyPoints(issue.getStoryPoints())
				.tags(new ArrayList<>(issue.getTags() != null ? issue.getTags() : List.of()))
				.build();
	}

	/** Diffs tracked fields and persists one activity entry per change. */
	private void recordChanges(Issue before, Issue after, User editor) {
		String actor = editor != null ? editor.getId() : null;
		List<IssueActivity> log = new ArrayList<>();
		add(log, after.getId(), actor, IssueActivity.Field.TITLE,
				before.getTitle(), after.getTitle());
		// Description bodies can be large; record that it changed, not the text.
		if (!Objects.equals(before.getDescription(), after.getDescription())) {
			log.add(entry(after.getId(), actor, IssueActivity.Field.DESCRIPTION, null, null));
		}
		add(log, after.getId(), actor, IssueActivity.Field.TYPE,
				name(before.getType()), name(after.getType()));
		add(log, after.getId(), actor, IssueActivity.Field.PRIORITY,
				name(before.getPriority()), name(after.getPriority()));
		add(log, after.getId(), actor, IssueActivity.Field.STATE,
				before.getState(), after.getState());
		add(log, after.getId(), actor, IssueActivity.Field.ASSIGNEE,
				before.getAssigneeId(), after.getAssigneeId());
		add(log, after.getId(), actor, IssueActivity.Field.SPRINT,
				before.getSprintId(), after.getSprintId());
		add(log, after.getId(), actor, IssueActivity.Field.START_DATE,
				str(before.getStartDate()), str(after.getStartDate()));
		add(log, after.getId(), actor, IssueActivity.Field.DUE_DATE,
				str(before.getDueDate()), str(after.getDueDate()));
		add(log, after.getId(), actor, IssueActivity.Field.ESTIMATE,
				str(before.getEstimateMinutes()), str(after.getEstimateMinutes()));
		add(log, after.getId(), actor, IssueActivity.Field.STORY_POINTS,
				str(before.getStoryPoints()), str(after.getStoryPoints()));
		List<String> beforeTags = before.getTags() != null ? before.getTags() : List.of();
		List<String> afterTags = after.getTags() != null ? after.getTags() : List.of();
		if (!beforeTags.equals(afterTags)) {
			log.add(entry(after.getId(), actor, IssueActivity.Field.TAGS,
					String.join(", ", beforeTags), String.join(", ", afterTags)));
		}
		if (!log.isEmpty()) activities.saveAll(log);
	}

	private void add(List<IssueActivity> log, String issueId, String actor,
			IssueActivity.Field field, String from, String to) {
		if (Objects.equals(from, to)) return;
		log.add(entry(issueId, actor, field, from, to));
	}

	private IssueActivity entry(String issueId, String actor,
			IssueActivity.Field field, String from, String to) {
		return IssueActivity.builder()
				.issueId(issueId)
				.actorId(actor)
				.field(field)
				.fromValue(from)
				.toValue(to)
				.build();
	}

	private String name(Enum<?> value) {
		return value != null ? value.name() : null;
	}

	private String str(Object value) {
		return value != null ? value.toString() : null;
	}

	/** Filtered, paginated search. Free-text is regex-escaped (NoSQL injection safe). */
	public Page<Issue> search(String projectId, String state, String assigneeId, String sprintId,
			String type, String text, boolean noSprint, int page, int size, User user) {
		Query query = new Query();
		// Non-admins only ever see issues from projects they belong to (A01).
		if (!user.isAdmin()) {
			List<String> visibleProjectIds = projects.visibleTo(user).stream()
					.map(Project::getId).toList();
			if (projectId != null && !visibleProjectIds.contains(projectId)) {
				return Page.empty(PageRequest.of(page, Math.min(size, 100)));
			}
			List<String> scope = projectId != null ? List.of(projectId) : visibleProjectIds;
			if (scope.isEmpty()) {
				return Page.empty(PageRequest.of(page, Math.min(size, 100)));
			}
			query.addCriteria(Criteria.where("projectId").in(scope));
		}
		else if (projectId != null) {
			query.addCriteria(Criteria.where("projectId").is(projectId));
		}
		if (state != null) query.addCriteria(Criteria.where("state").is(state));
		if (assigneeId != null) query.addCriteria(Criteria.where("assigneeId").is(assigneeId));
		if (sprintId != null) query.addCriteria(Criteria.where("sprintId").is(sprintId));
		else if (noSprint) query.addCriteria(Criteria.where("sprintId").is(null));
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
		assertAccess(issue, author);
		IssueComment comment = IssueComment.builder()
				.issueId(issue.getId())
				.authorId(author.getId())
				.text(text)
				.build();
		IssueComment saved = comments.save(comment);
		notifications.notifyIssueCommented(issue, author);
		return saved;
	}

	public List<IssueComment> commentsOf(String issueId, User user) {
		return comments.findByIssueIdOrderByCreatedAtAsc(getForUser(issueId, user).getId());
	}
}
