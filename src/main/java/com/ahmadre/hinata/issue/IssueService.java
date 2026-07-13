package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IssueService {

	// Distinct name: several methods use a local `log` list, so we can't rely on
	// Lombok's @Slf4j `log` field here.
	private static final Logger LOGGER = LoggerFactory.getLogger(IssueService.class);

	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final IssueActivityRepository activities;
	private final IssueLinkRepository links;
	private final IssueLinkEvents linkEvents;
	private final CommentEvents commentEvents;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final StorageService storage;
	private final WorkItemRepository workItems;
	private final AuditService audit;
	private final MongoTemplate mongo;

	/** Bucket "folder" isolating voice-message audio from other stored objects. */
	private static final String VOICE_PREFIX = "voice/";
	/** Audio MIME types accepted for voice comments (per recording platform). */
	private static final Set<String> VOICE_TYPES = Set.of(
			"audio/mp4", "audio/aac", "audio/x-m4a", "audio/m4a", "audio/mpeg",
			"audio/webm", "audio/ogg", "audio/wav", "audio/x-wav");
	/** Hard cap on a single voice message (guards memory + storage). */
	private static final long VOICE_MAX_BYTES = 16L * 1024 * 1024;
	/** Waveform bar count bounds — a malformed client can't flood the document. */
	private static final int VOICE_MAX_PEAKS = 96;

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

	/**
	 * Hard delete — restricted to platform admins, project leads and Team-Admins
	 * of a team owning the project (see {@link ProjectService#canDeleteIssues}).
	 * Regular members must archive instead.
	 */
	public void delete(String id, User user) {
		Issue issue = get(id);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		if (!projects.canDeleteIssues(project, user)) {
			throw ApiException.forbidden("error.issue.deleteForbidden");
		}
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
				workItems.deleteByIssueId(child.getId());
				deleteLinksOf(child.getId());
				issues.delete(child);
			}
		}
		comments.deleteByIssueId(issue.getId());
		activities.deleteByIssueId(issue.getId());
		workItems.deleteByIssueId(issue.getId());
		deleteLinksOf(issue.getId());
		issues.delete(issue);
		audit.event(AuditAction.ISSUE_DELETED).actor(user)
				.meta("issue", issue.getReadableId()).log();
	}

	/**
	 * Archives or restores an issue (soft delete). Open to every project member
	 * — the safe alternative to the role-gated hard {@link #delete}. Sub-tasks
	 * can't stand alone, so a standard issue takes its sub-tasks along.
	 */
	public Issue setArchived(String id, boolean archived, User user) {
		Issue issue = get(id);
		assertAccess(issue, user);
		if (issue.isArchived() == archived) return issue;
		Instant at = archived ? Instant.now() : null;
		if (issue.getType().isStandard()) {
			for (Issue child : issues.findByParentId(issue.getId())) {
				if (child.isArchived() != archived) {
					child.setArchived(archived);
					child.setArchivedAt(at);
					issues.save(child);
				}
			}
		}
		issue.setArchived(archived);
		issue.setArchivedAt(at);
		Issue saved = issues.save(issue);
		audit.event(archived ? AuditAction.ISSUE_ARCHIVED : AuditAction.ISSUE_UNARCHIVED)
				.actor(user).meta("issue", saved.getReadableId()).log();
		return saved;
	}

	/** Per-user capabilities on an issue, for the client's archive/delete UI. */
	public record Permissions(boolean canDelete) {
	}

	public Permissions permissionsOf(String id, User user) {
		Issue issue = get(id);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		return new Permissions(projects.canDeleteIssues(project, user));
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
		return search(projectId, state, assigneeId, sprintId, type, text, noSprint, false,
				page, size, user);
	}

	public Page<Issue> search(String projectId, String state, String assigneeId, String sprintId,
			String type, String text, boolean noSprint, boolean archived, int page, int size,
			User user) {
		Query query = new Query();
		// Archived issues are soft-deleted: hidden everywhere by default, listed
		// only when explicitly requested (the "Archived" view). `ne(true)` keeps
		// matching pre-migration documents that lack the flag.
		query.addCriteria(archived
				? Criteria.where("archived").is(true)
				: Criteria.where("archived").ne(true));
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
		return addComment(issueId, text, null, author);
	}

	/**
	 * Posts a text comment, optionally as a reply that quotes {@code replyToId}
	 * (WhatsApp-style). A dangling/foreign reply target is silently ignored so the
	 * comment is still posted.
	 */
	public IssueComment addComment(String issueId, String text, String replyToId, User author) {
		Issue issue = get(issueId);
		assertAccess(issue, author);
		IssueComment.IssueCommentBuilder builder = IssueComment.builder()
				.issueId(issue.getId())
				.authorId(author.getId())
				.text(text);
		applyReplyTo(builder, issue.getId(), replyToId);
		IssueComment saved = comments.save(builder.build());
		// Notifications are best-effort: a failure in the mail/push fan-out (e.g.
		// an SMTP hiccup, or a body the mail layer can't encode) must NEVER undo
		// the already-saved comment by bubbling a 500 back to the author.
		try {
			notifications.notifyComment(issue, author, text, saved);
		}
		catch (RuntimeException ex) {
			LOGGER.warn("comment notification failed for issue {} (comment kept)",
					issue.getId(), ex);
		}
		commentEvents.publishChanged(issue.getId());
		return saved;
	}

	/**
	 * Attaches the reply target to the builder, normalising to the thread ROOT:
	 * flat one-level threading means a reply to a reply shares the same
	 * {@code replyToId} (the root), so a thread stays a single flat list. The
	 * denormalised quote snapshot still records the DIRECT target. A dangling or
	 * foreign target is silently ignored (the comment posts as top-level).
	 */
	private void applyReplyTo(IssueComment.IssueCommentBuilder builder, String issueId,
			String replyToId) {
		if (replyToId == null || replyToId.isBlank()) {
			return;
		}
		IssueComment target = comments.findById(replyToId)
				.filter(c -> c.getIssueId().equals(issueId))
				.orElse(null);
		if (target == null) {
			return;
		}
		String rootId = (target.getReplyToId() != null && !target.getReplyToId().isBlank())
				? target.getReplyToId()
				: target.getId();
		builder.replyToId(rootId)
				.replyToAuthorId(target.getAuthorId())
				.replyToPreview(previewOf(target));
	}

	/** Compact one-line quote preview ("🎤" for voice) for a reply snapshot. */
	private String previewOf(IssueComment comment) {
		if (comment.resolvedType() == IssueComment.Type.VOICE) {
			return "🎤";
		}
		String text = comment.getText() == null ? "" : comment.getText().strip();
		text = text.replaceAll("\\s+", " ");
		return text.length() > 140 ? text.substring(0, 140) + "…" : text;
	}

	public Page<IssueComment> commentsOf(String issueId, int page, int size, User user) {
		return commentsOf(issueId, page, size, "newest", user);
	}

	/**
	 * One page of a thread's TOP-LEVEL comments (replies excluded), ordered by
	 * {@code sort} ("oldest" → oldest-first, else newest-first). Each comment's
	 * transient {@code replyCount} is populated in one batched aggregation.
	 */
	public Page<IssueComment> commentsOf(String issueId, int page, int size, String sort,
			User user) {
		String id = getForUser(issueId, user).getId();
		Sort.Direction dir = "oldest".equalsIgnoreCase(sort)
				? Sort.Direction.ASC
				: Sort.Direction.DESC;
		Page<IssueComment> result = comments.findByIssueIdAndReplyToIdIsNull(id,
				PageRequest.of(page, Math.min(size, 100), Sort.by(dir, "createdAt")));
		attachReplyCounts(result.getContent());
		return result;
	}

	/** One page of a root comment's replies, oldest-first (flat, one level). */
	public Page<IssueComment> repliesOf(String issueId, String rootId, int page, int size,
			User user) {
		IssueComment root = requireComment(issueId, rootId, user);
		return comments.findByReplyToId(root.getId(),
				PageRequest.of(page, Math.min(size, 100),
						Sort.by(Sort.Direction.ASC, "createdAt")));
	}

	/** Populates the transient {@code replyCount} on a page of top-level comments. */
	private void attachReplyCounts(List<IssueComment> roots) {
		if (roots.isEmpty()) {
			return;
		}
		List<String> ids = roots.stream().map(IssueComment::getId).toList();
		Map<String, Long> counts = new HashMap<>();
		// Never let a count hiccup break the comment list — default to 0 instead.
		try {
			for (IssueCommentRepository.ReplyCount rc : comments.countRepliesGrouped(ids)) {
				counts.put(rc.rootId(), rc.count());
			}
		}
		catch (RuntimeException ex) {
			LOGGER.warn("reply-count aggregation failed (counts default to 0)", ex);
		}
		for (IssueComment c : roots) {
			c.setReplyCount(counts.getOrDefault(c.getId(), 0L).intValue());
		}
	}

	/**
	 * Stores a recorded voice message as a {@link IssueComment.Type#VOICE}
	 * comment: the audio blob goes to object storage under {@code voice/}, while
	 * the pre-computed waveform peaks + duration are persisted inline so the feed
	 * renders the bubble without re-decoding the audio.
	 */
	public IssueComment addVoiceComment(String issueId, MultipartFile file, int durationMs,
			List<Integer> peaks, User author) {
		return addVoiceComment(issueId, file, durationMs, peaks, null, author);
	}

	public IssueComment addVoiceComment(String issueId, MultipartFile file, int durationMs,
			List<Integer> peaks, String replyToId, User author) {
		Issue issue = get(issueId);
		assertAccess(issue, author);
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("error.voice.empty");
		}
		String contentType = file.getContentType();
		if (contentType == null || !VOICE_TYPES.contains(contentType.toLowerCase())) {
			throw ApiException.badRequest("error.voice.notAudio");
		}
		if (file.getSize() > VOICE_MAX_BYTES) {
			throw ApiException.badRequest("error.voice.tooLarge");
		}
		byte[] data;
		try {
			data = file.getBytes();
		}
		catch (java.io.IOException ex) {
			throw ApiException.badRequest("error.storage.unreadableUpload");
		}
		// Clamp untrusted metadata: non-negative duration, at most VOICE_MAX_PEAKS
		// bars, each normalised into 0–100.
		int safeDuration = Math.max(0, durationMs);
		List<Integer> safePeaks = new ArrayList<>();
		if (peaks != null) {
			peaks.stream()
					.filter(Objects::nonNull)
					.limit(VOICE_MAX_PEAKS)
					.forEach(p -> safePeaks.add(Math.clamp(p, 0, 100)));
		}
		String objectKey = VOICE_PREFIX + java.util.UUID.randomUUID();
		storage.putObject(objectKey, data, contentType);
		IssueComment.Voice voice = IssueComment.Voice.builder()
				.objectKey(objectKey)
				.durationMs(safeDuration)
				.peaks(safePeaks)
				.size(file.getSize())
				.contentType(contentType)
				.build();
		IssueComment.IssueCommentBuilder builder = IssueComment.builder()
				.issueId(issue.getId())
				.authorId(author.getId())
				.type(IssueComment.Type.VOICE)
				.voice(voice);
		applyReplyTo(builder, issue.getId(), replyToId);
		IssueComment saved = comments.save(builder.build());
		// Blank text → notifyComment falls back to a "commented on <title>" body.
		// Best-effort (see addComment): never fail a saved voice comment on it.
		try {
			notifications.notifyComment(issue, author, "", saved);
		}
		catch (RuntimeException ex) {
			LOGGER.warn("voice-comment notification failed for issue {} (comment kept)",
					issue.getId(), ex);
		}
		commentEvents.publishChanged(issue.getId());
		return saved;
	}

	/** Authorised read of a voice comment's audio bytes for the bytes proxy. */
	public StorageService.StoredObject loadVoice(String issueId, String commentId, User user) {
		IssueComment comment = requireComment(issueId, commentId, user);
		if (comment.resolvedType() != IssueComment.Type.VOICE || comment.getVoice() == null) {
			throw ApiException.notFound("comment");
		}
		return storage.getObject(comment.getVoice().getObjectKey())
				.orElseThrow(() -> ApiException.notFound("voice"));
	}

	/** Edit a comment's text. Only the comment's own author may edit it. */
	public IssueComment editComment(String issueId, String commentId, String text, User editor) {
		IssueComment comment = requireComment(issueId, commentId, editor);
		if (!comment.getAuthorId().equals(editor.getId())) {
			throw ApiException.forbidden("error.comment.editOwnOnly");
		}
		if (comment.resolvedType() == IssueComment.Type.VOICE) {
			throw ApiException.badRequest("error.comment.voiceNotEditable");
		}
		comment.setText(text);
		comment.setEditedAt(Instant.now());
		IssueComment saved = comments.save(comment);
		commentEvents.publishChanged(comment.getIssueId());
		return withReplyCount(saved);
	}

	/**
	 * Delete a comment. The author may delete their own; admins may moderate any.
	 * Deleting a ROOT cascades its whole reply thread (and frees each reply's blob).
	 */
	public void deleteComment(String issueId, String commentId, User user) {
		IssueComment comment = requireComment(issueId, commentId, user);
		if (!user.isAdmin() && !comment.getAuthorId().equals(user.getId())) {
			throw ApiException.forbidden("error.comment.deleteOwnOnly");
		}
		// A top-level comment owns a flat reply thread — remove it too so replies
		// don't orphan, freeing any voice blobs they hold (best-effort).
		if (comment.getReplyToId() == null) {
			for (IssueComment reply : comments.findByReplyToId(comment.getId())) {
				if (reply.resolvedType() == IssueComment.Type.VOICE && reply.getVoice() != null) {
					storage.delete(reply.getVoice().getObjectKey());
				}
			}
			comments.deleteByReplyToId(comment.getId());
		}
		comments.delete(comment);
		// Free the audio blob backing a voice message (best-effort; delete() logs).
		if (comment.resolvedType() == IssueComment.Type.VOICE && comment.getVoice() != null) {
			storage.delete(comment.getVoice().getObjectKey());
		}
		commentEvents.publishChanged(comment.getIssueId());
	}

	/**
	 * Toggles the caller's emoji reaction on a comment (WhatsApp-style: one per
	 * user — a new emoji replaces theirs, the same emoji removes it). Any project
	 * member may react.
	 */
	public IssueComment reactToComment(String issueId, String commentId, String emoji, User user) {
		IssueComment comment = requireComment(issueId, commentId, user);
		String normalized = emoji == null ? "" : emoji.strip();
		if (normalized.isEmpty() || normalized.length() > 32) {
			throw ApiException.badRequest("error.comment.invalidReaction");
		}
		List<IssueComment.Reaction> reactions = comment.getReactions();
		if (reactions == null) {
			reactions = new ArrayList<>();
			comment.setReactions(reactions);
		}
		IssueComment.Reaction mine = reactions.stream()
				.filter(r -> user.getId().equals(r.getUserId()))
				.findFirst()
				.orElse(null);
		boolean sameEmoji = mine != null && normalized.equals(mine.getEmoji());
		reactions.removeIf(r -> user.getId().equals(r.getUserId()));
		if (!sameEmoji) {
			reactions.add(IssueComment.Reaction.builder()
					.emoji(normalized)
					.userId(user.getId())
					.createdAt(Instant.now())
					.build());
		}
		IssueComment saved = comments.save(comment);
		commentEvents.publishChanged(comment.getIssueId());
		return withReplyCount(saved);
	}

	/** Pins/unpins a comment. Any project member may pin and unpin. */
	public IssueComment setPinned(String issueId, String commentId, boolean pinned, User user) {
		IssueComment comment = requireComment(issueId, commentId, user);
		comment.setPinned(pinned);
		comment.setPinnedAt(pinned ? Instant.now() : null);
		IssueComment saved = comments.save(comment);
		commentEvents.publishChanged(comment.getIssueId());
		return withReplyCount(saved);
	}

	/** Pinned TOP-LEVEL comments of a thread, in pin order — surfaced above the feed. */
	public List<IssueComment> pinnedComments(String issueId, User user) {
		String id = getForUser(issueId, user).getId();
		List<IssueComment> pinned = comments.findByIssueIdAndPinnedIsTrueOrderByPinnedAtAsc(id)
				.stream()
				.filter(c -> c.getReplyToId() == null)
				.toList();
		attachReplyCounts(pinned);
		return pinned;
	}

	/** Sets the read-time reply count on a single comment (0 for a reply). */
	private IssueComment withReplyCount(IssueComment comment) {
		comment.setReplyCount((int) comments.countByReplyToId(comment.getId()));
		return comment;
	}

	private IssueComment requireComment(String issueId, String commentId, User user) {
		Issue issue = get(issueId);
		assertAccess(issue, user);
		return comments.findById(commentId)
				.filter(c -> c.getIssueId().equals(issue.getId()))
				.orElseThrow(() -> ApiException.notFound("comment"));
	}
}
