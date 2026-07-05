package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IssueService {

	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final IssueActivityRepository activities;
	private final IssueLinkRepository links;
	private final IssueLinkEvents linkEvents;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final MongoTemplate mongo;

	/** Internal lookup with no authorization check. */
	public Issue get(String idOrReadableId) {
		return issues.findById(idOrReadableId)
				.or(() -> issues.findByReadableIdIgnoreCase(idOrReadableId))
				.orElseThrow(() -> ApiException.notFound("issue"));
	}

	/** Internal lookup by canonical id; null when the issue no longer exists. */
	public Issue findOrNull(String id) {
		return id == null ? null : issues.findById(id).orElse(null);
	}

	/** Lookup that enforces the caller is a member of the issue's project (A01). */
	public Issue getForUser(String idOrReadableId, User user) {
		Issue issue = get(idOrReadableId);
		assertAccess(issue, user);
		return issue;
	}

	/** True when {@code user} may see the issue (admin or project member); never throws. */
	public boolean canAccess(Issue issue, User user) {
		try {
			assertAccess(issue, user);
			return true;
		}
		catch (RuntimeException denied) {
			return false;
		}
	}

	/** Throws 403 unless {@code user} is an admin or a member of the project. */
	private void assertAccess(Issue issue, User user) {
		projects.assertMember(projects.get(issue.getProjectId()), user);
	}

	public Issue create(Issue issue, User author) {
		Project project = projects.get(issue.getProjectId());
		// The builder sets the list field directly; normalise so the primary
		// assigneeId is in sync and the list is de-duped/blank-stripped.
		issue.setAssigneeIds(issue.getAssigneeIds());
		if (author != null) {
			projects.assertMember(project, author); // only project members may add issues (A01)
		}
		assignIssueNumber(issue, project);
		if (issue.getState() == null || !project.workflowStateNames().contains(issue.getState())) {
			issue.setState(project.workflowStateNames().get(0));
		}
		// An issue created straight into a sprint belongs on the sprint board, not
		// in the backlog — start it in the first working state.
		if (issue.getSprintId() != null && !issue.getSprintId().isBlank()) {
			promoteFromBacklog(issue, project);
		}
		if (author != null) {
			issue.setReporterId(author.getId());
		}
		validateHierarchy(issue);
		issue.setRank(Instant.now().toEpochMilli());
		Issue saved = saveWithNumberRetry(issue, project);
		mergeProjectLabels(project, saved.getTags());
		activities.save(IssueActivity.builder()
				.issueId(saved.getId())
				.actorId(author != null ? author.getId() : null)
				.field(IssueActivity.Field.CREATED)
				.build());
		// Notify every assignee (except the creator) that the issue is theirs.
		notifications.notifyAssigned(saved, author, saved.getAssigneeIds());
		// Ping anyone @-mentioned in the freshly written description.
		notifications.notifyNewMentions(saved, author, null, saved.getDescription());
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
		Set<String> previousAssignees = new HashSet<>(
				issue.getAssigneeIds() != null ? issue.getAssigneeIds() : List.of());
		String previousState = issue.getState();
		String previousSprint = issue.getSprintId();
		mutator.accept(issue);
		validateHierarchy(issue);

		Project project = projects.get(issue.getProjectId());
		// Keep the issue's state in step with its sprint membership:
		//  • pulled into a sprint  → advance out of Backlog (it's now on the board);
		//  • returned to backlog   → drop back to the Backlog state.
		boolean hadSprint = previousSprint != null && !previousSprint.isBlank();
		boolean hasSprint = issue.getSprintId() != null && !issue.getSprintId().isBlank();
		if (!hadSprint && hasSprint) {
			promoteFromBacklog(issue, project);
		}
		else if (hadSprint && !hasSprint) {
			demoteToBacklog(issue, project);
		}
		if (!project.workflowStateNames().contains(issue.getState())) {
			throw ApiException.badRequest("error.issue.unknownState", issue.getState());
		}
		boolean nowResolved = project.getResolvedStates().contains(issue.getState());
		issue.setResolvedAt(nowResolved
				? (issue.getResolvedAt() != null ? issue.getResolvedAt() : Instant.now())
				: null);

		Issue saved = issues.save(issue);
		mergeProjectLabels(project, saved.getTags());
		recordChanges(before, saved, editor);
		// Ping anyone newly @-mentioned in the description (existing mentions on an
		// unrelated edit are not re-notified).
		notifications.notifyNewMentions(saved, editor, before.getDescription(), saved.getDescription());
		Set<String> newlyAssigned = new HashSet<>(
				saved.getAssigneeIds() != null ? saved.getAssigneeIds() : List.of());
		newlyAssigned.removeAll(previousAssignees);
		if (!newlyAssigned.isEmpty()) {
			notifications.notifyAssigned(saved, editor, newlyAssigned);
		}
		else if (!saved.getState().equals(previousState)) {
			notifications.notifyStateChanged(saved, editor, saved.getState());
		}
		return saved;
	}

	/**
	 * Enforces the Epic → standard → sub-task hierarchy (see {@link Issue.Type}):
	 * an epic never has a parent, a sub-task always does, a standard issue parents
	 * to an epic, and a sub-task parents to a standard issue. The parent must live
	 * in the same project, and a type change may not strand an existing child.
	 */
	private void validateHierarchy(Issue issue) {
		Issue.Type type = issue.getType();
		String parentId = issue.getParentId();
		boolean hasParent = parentId != null && !parentId.isBlank();

		if (type.isEpic() && hasParent) {
			throw ApiException.badRequest("error.issue.epicNoParent");
		}
		if (type.isSubtask() && !hasParent) {
			throw ApiException.badRequest("error.issue.subtaskNeedsParent");
		}
		if (hasParent) {
			if (parentId.equals(issue.getId())) {
				throw ApiException.badRequest("error.issue.parentSelf");
			}
			Issue parent = get(parentId);
			if (!Objects.equals(parent.getProjectId(), issue.getProjectId())) {
				throw ApiException.badRequest("error.issue.parentOtherProject");
			}
			boolean parentOk = type.isSubtask()
					? parent.getType().isStandard()
					: parent.getType().isEpic();
			if (!parentOk) {
				throw ApiException.badRequest("error.issue.parentWrongType", parent.getReadableId());
			}
		}
		// A type change must keep every existing child link legal: sub-tasks need a
		// standard parent, standard children need an epic parent.
		if (issue.getId() != null) {
			for (Issue child : issues.findByParentId(issue.getId())) {
				boolean stillOk = child.getType().isSubtask()
						? type.isStandard()
						: type.isEpic();
				if (!stillOk) {
					throw ApiException.badRequest("error.issue.typeChangeHasChildren");
				}
			}
		}
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
		for (String state : project.workflowStateNames()) {
			if (!isBacklogState(state)) {
				issue.setState(state);
				return;
			}
		}
	}

	/** Returns an issue to the Backlog state when it leaves a sprint, when the
	 * project actually has a backlog state. */
	private void demoteToBacklog(Issue issue, Project project) {
		if (isBacklogState(issue.getState())) {
			return;
		}
		for (String state : project.workflowStateNames()) {
			if (isBacklogState(state)) {
				issue.setState(state);
				return;
			}
		}
	}

	public void delete(String id, User user) {
		Issue issue = get(id);
		assertAccess(issue, user);
		if (issue.getType().isEpic()) {
			// Standard children survive as top-level issues — just drop the epic link.
			mongo.updateMulti(new Query(Criteria.where("parentId").is(issue.getId())),
					new Update().unset("parentId"), Issue.class);
		}
		else if (issue.getType().isStandard()) {
			// Sub-tasks can't exist without their parent → cascade-delete them.
			for (Issue child : issues.findByParentId(issue.getId())) {
				comments.deleteByIssueId(child.getId());
				activities.deleteByIssueId(child.getId());
				deleteLinksOf(child.getId());
				issues.delete(child);
			}
		}
		comments.deleteByIssueId(issue.getId());
		activities.deleteByIssueId(issue.getId());
		deleteLinksOf(issue.getId());
		issues.delete(issue);
	}

	/**
	 * Removes every issue link touching {@code issueId} (either end) so a deleted
	 * issue leaves no dangling links, and pings the issue on the other end of each
	 * so its open detail view drops the stale row live.
	 */
	private void deleteLinksOf(String issueId) {
		List<IssueLink> touching = links.findBySourceIdOrTargetId(issueId, issueId);
		if (touching.isEmpty()) return;
		for (IssueLink link : touching) {
			String other = link.getSourceId().equals(issueId)
					? link.getTargetId() : link.getSourceId();
			linkEvents.publishChanged(other);
		}
		links.deleteAll(touching);
	}

	// ── hierarchy ───────────────────────────────────────────────────────────

	/** Breadcrumb ancestors (root → immediate parent) plus direct children. */
	public record Hierarchy(List<Issue> ancestors, List<Issue> children) {
	}

	public Hierarchy hierarchyOf(String idOrReadableId, User user) {
		Issue issue = getForUser(idOrReadableId, user);
		LinkedList<Issue> ancestors = new LinkedList<>();
		String parentId = issue.getParentId();
		int guard = 0; // cycle guard — the hierarchy is at most 3 levels deep
		while (parentId != null && !parentId.isBlank() && guard++ < 5) {
			Issue parent = issues.findById(parentId).orElse(null);
			if (parent == null) break;
			ancestors.addFirst(parent);
			parentId = parent.getParentId();
		}
		List<Issue> children = new ArrayList<>(issues.findByParentId(issue.getId()));
		children.sort(Comparator.comparingLong(Issue::getNumberInProject));
		return new Hierarchy(ancestors, children);
	}

	/** Permanently deletes a label from a project: removes it from the project's
	 * vocabulary and pulls it from every issue in the project that carries it. */
	public void removeProjectLabel(String projectId, String label, User user) {
		Project project = projects.get(projectId);
		if (user != null) projects.assertMember(project, user);
		if (project.getLabels() != null
				&& project.getLabels().removeIf(l -> l.getName().equals(label))) {
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
		List<Project.Label> labels = project.getLabels();
		if (labels == null) {
			labels = new ArrayList<>();
			project.setLabels(labels);
		}
		java.util.Set<String> existing = new java.util.HashSet<>(project.labelNames());
		boolean changed = false;
		for (String tag : tags) {
			if (tag != null && !tag.isBlank() && existing.add(tag)) {
				labels.add(Project.Label.builder()
						.id(Project.newId())
						.name(tag)
						.hue(Project.labelHueAt(labels.size()))
						.build());
				changed = true;
			}
		}
		if (changed) projects.save(project);
	}

	public Page<IssueActivity> activityOf(String issueId, int page, int size, User user) {
		String id = getForUser(issueId, user).getId();
		return activities.findByIssueIdOrderByCreatedAtDesc(id,
				PageRequest.of(page, Math.min(size, 100)));
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
				.assigneeIds(new ArrayList<>(issue.getAssigneeIds() != null ? issue.getAssigneeIds() : List.of()))
				.parentId(issue.getParentId())
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
		add(log, after.getId(), actor, IssueActivity.Field.PARENT,
				before.getParentId(), after.getParentId());
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
		// Everyone is limited to active (non-archived) projects — an archived
		// project is deactivated, so its issues never surface anywhere. Non-admins
		// are further limited to projects they belong to (A01). visibleTo already
		// excludes archived projects, so it is the active scope for a member.
		List<String> scope = user.isAdmin()
				? List.copyOf(projects.activeProjectIds())
				: projects.visibleTo(user).stream().map(Project::getId).toList();
		if (projectId != null) {
			if (!scope.contains(projectId)) {
				return Page.empty(PageRequest.of(page, Math.min(size, 100)));
			}
			query.addCriteria(Criteria.where("projectId").is(projectId));
		}
		else {
			if (scope.isEmpty()) {
				return Page.empty(PageRequest.of(page, Math.min(size, 100)));
			}
			query.addCriteria(Criteria.where("projectId").in(scope));
		}
		if (state != null) query.addCriteria(Criteria.where("state").is(state));
		// Match on membership in the assignee list (covers primary + secondary
		// assignees); the legacy single field is included for un-migrated docs.
		if (assigneeId != null) {
			query.addCriteria(new Criteria().orOperator(
					Criteria.where("assigneeIds").is(assigneeId),
					Criteria.where("assigneeId").is(assigneeId)));
		}
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
		notifications.notifyComment(issue, author, text);
		return saved;
	}

	public Page<IssueComment> commentsOf(String issueId, int page, int size, User user) {
		String id = getForUser(issueId, user).getId();
		return comments.findByIssueIdOrderByCreatedAtDesc(id,
				PageRequest.of(page, Math.min(size, 100)));
	}

	/** Edit a comment's text. Only the comment's own author may edit it. */
	public IssueComment editComment(String issueId, String commentId, String text, User editor) {
		IssueComment comment = requireComment(issueId, commentId, editor);
		if (!comment.getAuthorId().equals(editor.getId())) {
			throw ApiException.forbidden("error.comment.editOwnOnly");
		}
		comment.setText(text);
		return comments.save(comment);
	}

	/** Delete a comment. The author may delete their own; admins may moderate any. */
	public void deleteComment(String issueId, String commentId, User user) {
		IssueComment comment = requireComment(issueId, commentId, user);
		if (!user.isAdmin() && !comment.getAuthorId().equals(user.getId())) {
			throw ApiException.forbidden("error.comment.deleteOwnOnly");
		}
		comments.delete(comment);
	}

	private IssueComment requireComment(String issueId, String commentId, User user) {
		Issue issue = get(issueId);
		assertAccess(issue, user);
		return comments.findById(commentId)
				.filter(c -> c.getIssueId().equals(issue.getId()))
				.orElseThrow(() -> ApiException.notFound("comment"));
	}
}
