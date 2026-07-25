package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.project.WorkflowMapping;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Moves issues from one project to another — Hinata's equivalent of Jira's Move
 * wizard, including the status mapping a cross-project move requires.
 *
 * <p>An issue's {@code state} references a state <em>name</em> of its project's
 * workflow, and {@code IssueService.update} rejects any state the project does
 * not define. Two projects therefore only rarely share a workflow, so a move
 * must decide, per source status, where it lands in the target. That decision is
 * pre-computed by {@link #preflight} (using the shared {@link WorkflowMapping}
 * ladder), shown to the user, and passed back to {@link #move} — nothing is ever
 * silently remapped.
 *
 * <p>Everything else that must follow the issue across the project boundary is
 * handled here as well: the project-scoped issue number and its denormalized
 * {@code readableId}, the mirrored {@code git_dev_info.issueKey}, sprint
 * membership, the parent link, and the label vocabulary. Comments, activity,
 * attachments, work items and issue links reference the issue by its (stable)
 * id and need no migration.
 *
 * <p>Structural rules, mirroring Jira but fixing its best-known trap:
 * <ul>
 *   <li>Sub-tasks always travel with their parent — they cannot stand alone in
 *       another project, so a sub-task may not be moved on its own.</li>
 *   <li>An epic's children stay behind by default (Jira's behaviour) but can be
 *       taken along with an explicit opt-in, which is what users almost always
 *       actually want.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueMoveService {

	/** Ceiling on one move operation — keeps a bulk move a bounded transaction. */
	public static final int MAX_BATCH = 100;

	/** How many former ids an issue keeps; long enough for any real move history. */
	private static final int MAX_FORMER_IDS = 20;

	private static final String GIT_DEV_INFO = "git_dev_info";

	private final IssueRepository issues;
	private final IssueActivityRepository activities;
	private final IssueService issueService;
	private final ProjectService projects;
	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final AuditService audit;
	private final MongoTemplate mongo;

	// --- API shapes ----------------------------------------------------------

	/** Why a move needs the user's attention. Rendered from an i18n key client-side. */
	public enum WarningCode {
		/** The issue leaves its sprint because the sprint's board doesn't span the target. */
		SPRINT_DETACHED,
		/** The parent stays behind, so the issue arrives without its epic / parent link. */
		PARENT_DETACHED,
		/** An epic is moving but its children were not included. */
		EPIC_CHILDREN_STAY,
		/** An assignee is not a member of the target project. */
		ASSIGNEE_NOT_MEMBER
	}

	/**
	 * One row of the status mapping: where issues currently in {@code fromState}
	 * should land. {@code existsInTarget} tells the UI whether this is a free
	 * carry-over (green) or a real decision the user must confirm (amber).
	 */
	public record StateMapping(String fromState, String suggestedTo, boolean existsInTarget,
			int issueCount) {
	}

	/** One issue in the move, with a preview of the id it will carry afterwards. */
	public record MovePreview(String issueId, String readableId, String nextReadableId, String title,
			Issue.Type type, String state, boolean pulledIn) {
	}

	public record MoveWarning(WarningCode code, String readableId, String detail) {
	}

	public record Preflight(Project targetProject, List<String> targetStates,
			List<StateMapping> stateMappings, List<MovePreview> issues, List<MoveWarning> warnings) {
	}

	// --- preflight -----------------------------------------------------------

	/**
	 * Analyses a prospective move without changing anything: which issues travel
	 * (including implicitly pulled-in children), which statuses need mapping and
	 * what they'd default to, and everything the user should know before
	 * confirming.
	 */
	public Preflight preflight(List<String> issueIds, String targetProjectId,
			boolean includeEpicChildren, User user) {
		Project target = projects.get(targetProjectId);
		projects.assertMember(target, user);

		Plan plan = plan(issueIds, target, includeEpicChildren, user);

		// One mapping row per distinct source status, so a bulk move asks the
		// question once rather than once per issue.
		Map<String, StateMapping> mappings = new LinkedHashMap<>();
		Map<String, Integer> counts = new HashMap<>();
		for (Issue issue : plan.ordered()) {
			String state = issue.getState();
			counts.merge(state, 1, Integer::sum);
			if (mappings.containsKey(state)) continue;
			Project source = plan.projectOf(issue);
			String suggested = WorkflowMapping.suggest(source, state, target);
			mappings.put(state, new StateMapping(state, suggested,
					WorkflowMapping.contains(target, state), 0));
		}
		List<StateMapping> stateMappings = new ArrayList<>();
		for (StateMapping mapping : mappings.values()) {
			stateMappings.add(new StateMapping(mapping.fromState(), mapping.suggestedTo(),
					mapping.existsInTarget(), counts.getOrDefault(mapping.fromState(), 0)));
		}

		// The id preview is indicative only: the real numbers are reserved at move
		// time, so a concurrent create can shift them. Numbering from the current
		// counter keeps the preview stable and monotonic.
		long nextNumber = target.getIssueCounter();
		List<MovePreview> previews = new ArrayList<>();
		for (Issue issue : plan.ordered()) {
			nextNumber++;
			previews.add(new MovePreview(issue.getId(), issue.getReadableId(),
					target.getKey() + "-" + nextNumber, issue.getTitle(), issue.getType(),
					issue.getState(), !plan.selected().contains(issue.getId())));
		}

		return new Preflight(target, target.workflowStateNames(), stateMappings, previews,
				plan.warnings());
	}

	// --- move ----------------------------------------------------------------

	/**
	 * Executes the move confirmed by the user. {@code stateMap} maps each source
	 * status to a status of the target workflow; a status left unmapped falls back
	 * to the same suggestion the preflight showed, so a client that skipped the
	 * mapping step still produces a valid result rather than a 400.
	 */
	@org.springframework.transaction.annotation.Transactional
	public List<Issue> move(List<String> issueIds, String targetProjectId,
			Map<String, String> stateMap, boolean includeEpicChildren, boolean keepSprint,
			User user) {
		Project target = projects.get(targetProjectId);
		projects.assertMember(target, user);

		Plan plan = plan(issueIds, target, includeEpicChildren, user);
		Set<String> moving = plan.movingIds();

		// Before the first write: make sure the numbers we are about to reserve are
		// actually free. See reconcileIssueCounter — inside a transaction there is
		// no second chance to heal this.
		reconcileIssueCounter(target);

		List<Issue> moved = new ArrayList<>();
		for (Issue issue : plan.ordered()) {
			Project source = plan.projectOf(issue);
			if (Objects.equals(source.getId(), target.getId())) continue; // already there
			moved.add(moveOne(issue, source, target, stateMap, moving, keepSprint, user));
		}
		return moved;
	}

	private Issue moveOne(Issue issue, Project source, Project target, Map<String, String> stateMap,
			Set<String> moving, boolean keepSprint, User user) {
		String previousReadableId = issue.getReadableId();

		// Status: the confirmed mapping, else the same suggestion the preflight
		// showed. Validated against the target workflow either way, so a client
		// cannot write a status the project does not define.
		String requested = stateMap != null ? stateMap.get(issue.getState()) : null;
		String nextState = requested != null
				? WorkflowMapping.canonical(target, requested)
				: WorkflowMapping.suggest(source, issue.getState(), target);
		if (nextState == null) {
			throw ApiException.badRequest("error.issue.moveStateUnmapped", issue.getState());
		}

		// The parent link only survives when the parent travels too — an issue's
		// parent must live in the same project (see IssueService.validateHierarchy).
		if (issue.getParentId() != null && !moving.contains(issue.getParentId())) {
			issue.setParentId(null);
		}

		// A sprint belongs to a board; it stays valid only while that board also
		// spans the target project.
		if (issue.getSprintId() != null && !(keepSprint && sprintSpans(issue.getSprintId(), target))) {
			issue.setSprintId(null);
		}

		issue.setProjectId(target.getId());
		issue.setState(nextState);
		boolean resolved = target.getResolvedStates() != null
				&& target.getResolvedStates().contains(nextState);
		issue.setResolvedAt(resolved
				? (issue.getResolvedAt() != null ? issue.getResolvedAt() : Instant.now())
				: null);
		rememberFormerId(issue, previousReadableId);
		assignNumber(issue, target);

		Issue saved = save(issue);
		issueService.mergeProjectLabels(target, saved.getTags());
		reKeyDevInfo(previousReadableId, saved.getReadableId(), target.getId());

		activities.save(IssueActivity.builder()
				.issueId(saved.getId())
				.actorId(user != null ? user.getId() : null)
				.field(IssueActivity.Field.PROJECT)
				.fromValue(previousReadableId)
				.toValue(saved.getReadableId())
				.build());
		audit.event(AuditAction.ISSUE_MOVED).actor(user)
				.meta("issue", previousReadableId)
				.meta("target", saved.getReadableId())
				.log();
		return saved;
	}

	// --- planning ------------------------------------------------------------

	/**
	 * The resolved move set: the explicitly selected issues plus everything that
	 * must (sub-tasks) or was asked to (epic children) come along, in an order
	 * where a parent precedes its children, together with the warnings the user
	 * should see. Source projects are resolved once and cached — a selection may
	 * legitimately span several projects.
	 */
	private record Plan(List<Issue> ordered, Set<String> selected, Map<String, Project> sources,
			List<MoveWarning> warnings) {

		Project projectOf(Issue issue) {
			return sources.get(issue.getProjectId());
		}

		Set<String> movingIds() {
			Set<String> ids = new LinkedHashSet<>();
			for (Issue issue : ordered) ids.add(issue.getId());
			return ids;
		}
	}

	private Plan plan(List<String> issueIds, Project target, boolean includeEpicChildren, User user) {
		if (issueIds == null || issueIds.isEmpty()) {
			throw ApiException.badRequest("error.issue.moveNoIssues");
		}
		if (issueIds.size() > MAX_BATCH) {
			throw ApiException.badRequest("error.issue.moveTooMany", MAX_BATCH);
		}

		Set<String> selected = new LinkedHashSet<>(issueIds);
		Map<String, Issue> resolved = new LinkedHashMap<>();
		Map<String, Project> sources = new HashMap<>();
		List<MoveWarning> warnings = new ArrayList<>();

		for (String id : selected) {
			Issue issue = issueService.get(id);
			Project source = sourceProject(issue, sources, user);
			// A sub-task cannot exist without its parent in the same project, so it
			// is never movable on its own — the user must move the parent.
			if (issue.getType().isSubtask() && !selected.contains(issue.getParentId())) {
				throw ApiException.badRequest("error.issue.moveSubtaskNeedsParent",
						issue.getReadableId());
			}
			if (Objects.equals(source.getId(), target.getId())) {
				throw ApiException.badRequest("error.issue.moveSameProject", issue.getReadableId());
			}
			resolved.put(issue.getId(), issue);
		}

		// Pull in children. Sub-tasks always follow their parent; an epic's children
		// only on explicit opt-in — otherwise they stay behind (as Jira does) and we
		// say so instead of leaving the user to find out later.
		for (Issue issue : List.copyOf(resolved.values())) {
			List<Issue> children = issues.findByParentId(issue.getId());
			if (children.isEmpty()) continue;
			if (issue.getType().isEpic() && !includeEpicChildren) {
				warnings.add(new MoveWarning(WarningCode.EPIC_CHILDREN_STAY, issue.getReadableId(),
						String.valueOf(children.size())));
				continue;
			}
			for (Issue child : children) {
				if (resolved.containsKey(child.getId())) continue;
				sourceProject(child, sources, user);
				resolved.put(child.getId(), child);
				// An epic's standard children carry their own sub-tasks along.
				for (Issue grandChild : issues.findByParentId(child.getId())) {
					resolved.putIfAbsent(grandChild.getId(), grandChild);
					sourceProject(grandChild, sources, user);
				}
			}
		}

		List<Issue> ordered = new ArrayList<>(resolved.values());
		collectWarnings(ordered, resolved.keySet(), target, warnings);
		return new Plan(ordered, selected, sources, warnings);
	}

	private Project sourceProject(Issue issue, Map<String, Project> cache, User user) {
		return cache.computeIfAbsent(issue.getProjectId(), id -> {
			Project project = projects.get(id);
			projects.assertMember(project, user); // may only move out of a project one belongs to
			return project;
		});
	}

	private void collectWarnings(List<Issue> ordered, Set<String> movingIds, Project target,
			List<MoveWarning> warnings) {
		List<String> members = target.getMemberIds() != null ? target.getMemberIds() : List.of();
		Set<String> flaggedAssignees = new LinkedHashSet<>();
		for (Issue issue : ordered) {
			if (issue.getParentId() != null && !movingIds.contains(issue.getParentId())) {
				Issue parent = issueService.findOrNull(issue.getParentId());
				warnings.add(new MoveWarning(WarningCode.PARENT_DETACHED, issue.getReadableId(),
						parent != null ? parent.getReadableId() : null));
			}
			if (issue.getSprintId() != null && !sprintSpans(issue.getSprintId(), target)) {
				warnings.add(new MoveWarning(WarningCode.SPRINT_DETACHED, issue.getReadableId(),
						sprintName(issue.getSprintId())));
			}
			for (String assignee : issue.getAssigneeIds()) {
				if (!members.contains(assignee) && flaggedAssignees.add(assignee)) {
					warnings.add(new MoveWarning(WarningCode.ASSIGNEE_NOT_MEMBER,
							issue.getReadableId(), assignee));
				}
			}
		}
	}

	// --- mechanics -----------------------------------------------------------

	/** Whether the sprint's board also spans {@code target}, so the sprint survives. */
	private boolean sprintSpans(String sprintId, Project target) {
		return sprints.findById(sprintId)
				.flatMap(sprint -> boards.findById(sprint.getBoardId()))
				.map(AgileBoard::getProjectIds)
				.map(ids -> ids.contains(target.getId()))
				.orElse(false);
	}

	private String sprintName(String sprintId) {
		return sprints.findById(sprintId).map(Sprint::getName).orElse(null);
	}

	/** Reserves the next number in the target project and rewrites the readable id. */
	private void assignNumber(Issue issue, Project target) {
		long number = projects.nextIssueNumber(target.getId());
		issue.setNumberInProject(number);
		issue.setReadableId(target.getKey() + "-" + number);
	}

	/**
	 * Lifts the target's {@code issueCounter} to the highest number its issues
	 * actually carry, so every number handed out below is free.
	 *
	 * <p>A counter can lag behind the real data whenever issues arrived without
	 * going through it — a restored dump, an import. {@code IssueService.create}
	 * heals that lazily: it lets the unique {@code (projectId, numberInProject)}
	 * index reject the insert, then raises the counter and retries. A move cannot
	 * do that. It runs in a transaction, and on a replica set a <em>failed write
	 * aborts the whole transaction</em> — every later operation in it, including
	 * the healing read itself, comes back {@code NoSuchTransaction (251)}. So the
	 * counter is reconciled here, before the first write, where it still can be.
	 */
	private void reconcileIssueCounter(Project target) {
		long maxExisting = issues.findTopByProjectIdOrderByNumberInProjectDesc(target.getId())
				.map(Issue::getNumberInProject)
				.orElse(0L);
		if (maxExisting > target.getIssueCounter()) {
			projects.ensureIssueCounterAtLeast(target.getId(), maxExisting);
		}
	}

	/**
	 * Saves inside the move's transaction. With the counter reconciled up front, a
	 * collision here can only be a concurrent create that reserved the same number
	 * in between — nothing this transaction can repair, so it is reported as a
	 * conflict the caller may simply retry rather than as a server error.
	 */
	private Issue save(Issue issue) {
		try {
			return issues.save(issue);
		}
		catch (org.springframework.dao.DuplicateKeyException collision) {
			log.warn("Issue number {} was taken while moving {}", issue.getNumberInProject(),
					issue.getId(), collision);
			throw ApiException.conflict("error.issue.moveNumberTaken");
		}
	}

	/**
	 * Appends the id the issue is leaving behind, so links shared under it keep
	 * resolving. Bounded and de-duplicated: moving back and forth must not grow
	 * the document without limit.
	 */
	private void rememberFormerId(Issue issue, String previousReadableId) {
		if (previousReadableId == null || previousReadableId.isBlank()) return;
		// Pre-migration documents have no array at all — @Builder.Default does not
		// apply when Spring Data maps an existing document.
		List<String> former = issue.getFormerReadableIds();
		if (former == null) {
			former = new ArrayList<>();
			issue.setFormerReadableIds(former);
		}
		String normalized = previousReadableId.toUpperCase(Locale.ROOT);
		former.remove(normalized);
		former.add(normalized);
		while (former.size() > MAX_FORMER_IDS) former.remove(0);
	}

	/**
	 * Follows the issue's dev-info document to its new key + project. The
	 * {@code issueKey} is the unique link between an issue and the branches /
	 * commits / PRs that mention it, so leaving it on the old key would silently
	 * orphan the whole development panel.
	 */
	private void reKeyDevInfo(String previousReadableId, String readableId, String projectId) {
		if (previousReadableId == null) return;
		mongo.updateFirst(
				Query.query(Criteria.where("issueKey").regex("^"
						+ java.util.regex.Pattern.quote(previousReadableId) + "$", "i")),
				new Update().set("issueKey", readableId).set("projectId", projectId),
				GIT_DEV_INFO);
	}
}
