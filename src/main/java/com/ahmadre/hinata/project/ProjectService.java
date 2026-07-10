package com.ahmadre.hinata.project;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.team.TeamAccess;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

	private static final String ISSUES = "issues";
	private static final String GIT_DEV_INFO = "git_dev_info";
	private static final String PROJECT_ID = "projectId";
	private static final String STATE = "state";
	private static final String RESOLVED_AT = "resolvedAt";
	private static final String READABLE_ID = "readableId";
	private static final String ISSUE_KEY = "issueKey";

	private final ProjectRepository projects;
	private final MongoTemplate mongo;
	// Injected as a repository (not TeamService) so project gating can read team
	// access without forming a bean cycle (TeamService depends on this service).
	private final TeamRepository teams;
	// Safe to inject: NotificationService does not depend back on this service.
	private final NotificationService notifications;

	public Project get(String id) {
		return projects.findById(id).orElseThrow(() -> ApiException.notFound("project"));
	}

	public Optional<Project> findOptional(String id) {
		return projects.findById(id);
	}

	/**
	 * Projects the user may see: platform admins see all; everyone else sees the
	 * projects they are a direct member of <em>plus</em> any project granted to
	 * them through a team (see {@link TeamAccess}). The two sources are deduped
	 * by id and archived projects are excluded.
	 */
	public List<Project> visibleTo(User user) {
		return visible(user, false);
	}

	/** Archived projects the user may see (for the Projects "Archived" tab). */
	public List<Project> archivedVisibleTo(User user) {
		return visible(user, true);
	}

	private List<Project> visible(User user, boolean archived) {
		if (user.isAdmin()) {
			return archived ? projects.findByArchivedTrue() : projects.findByArchivedFalse();
		}
		List<Project> direct = archived
				? projects.findByMemberIdsContainsAndArchivedTrue(user.getId())
				: projects.findByMemberIdsContainsAndArchivedFalse(user.getId());
		Set<String> granted = teamGrantedProjectIds(user);
		if (granted.isEmpty()) return direct;
		Set<String> seen = new HashSet<>();
		List<Project> result = new ArrayList<>();
		for (Project project : direct) {
			if (seen.add(project.getId())) result.add(project);
		}
		granted.removeAll(seen);
		for (Project project : projects.findAllById(granted)) {
			if (project.isArchived() == archived && seen.add(project.getId())) result.add(project);
		}
		return result;
	}

	/**
	 * Ids of all non-archived (active) projects. An archived project is treated
	 * as deactivated platform-wide, so its issues, boards and other data must
	 * never surface anywhere; callers listing such data restrict to this set.
	 */
	public Set<String> activeProjectIds() {
		Set<String> ids = new HashSet<>();
		for (Project project : projects.findByArchivedFalse()) {
			ids.add(project.getId());
		}
		return ids;
	}

	/** Whether the project exists and is active (non-archived). */
	public boolean isActive(String projectId) {
		return projects.findById(projectId).map(p -> !p.isArchived()).orElse(false);
	}

	public Project create(Project project, User creator) {
		String key = project.getKey().toUpperCase(Locale.ROOT);
		if (!key.matches("[A-Z][A-Z0-9]{1,9}")) {
			throw ApiException.badRequest("error.project.invalidKey");
		}
		if (projects.existsByKeyIgnoreCase(key)) {
			throw ApiException.conflict("error.project.keyExists");
		}
		project.setKey(key);
		if (project.getLeadId() == null) {
			project.setLeadId(creator.getId());
		}
		if (project.getLeadIds() == null || project.getLeadIds().isEmpty()) {
			project.setLeadIds(new ArrayList<>(List.of(project.getLeadId())));
		}
		if (!project.getMemberIds().contains(creator.getId())) {
			project.getMemberIds().add(creator.getId());
		}
		// Every lead is implicitly a member.
		for (String lead : project.getLeadIds()) {
			if (!project.getMemberIds().contains(lead)) project.getMemberIds().add(lead);
		}
		return projects.save(project);
	}

	public Project save(Project project) {
		return projects.save(project);
	}

	/**
	 * Raises the project's {@code issueCounter} to at least [floor] if it has
	 * fallen behind the real data (e.g. imported/seeded issues). Uses Mongo's
	 * {@code $max} so concurrent callers can't lower it.
	 */
	public void ensureIssueCounterAtLeast(String projectId, long floor) {
		mongo.updateFirst(
				Query.query(Criteria.where("_id").is(projectId)),
				new Update().max("issueCounter", floor),
				Project.class);
	}

	/** Atomically reserves the next issue number for the project. */
	public long nextIssueNumber(String projectId) {
		Project updated = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(projectId)),
				new Update().inc("issueCounter", 1),
				FindAndModifyOptions.options().returnNew(true),
				Project.class);
		if (updated == null) {
			throw ApiException.notFound("project");
		}
		return updated.getIssueCounter();
	}

	public void assertMember(Project project, User user) {
		if (user.isAdmin() || project.getMemberIds().contains(user.getId())) return;
		// A team grant (Team-Admin, or member with ALL/SOME access covering this
		// project) is equivalent to direct project membership for visibility.
		if (teamGrantedProjectIds(user).contains(project.getId())) return;
		throw ApiException.forbidden("error.project.notMember");
	}

	/** Editing project settings is restricted to platform admins and project
	 * leads — regular members can read but not reconfigure the project. */
	public void assertLeadOrAdmin(Project project, User user) {
		if (user.isAdmin()) return;
		List<String> leads = project.getLeadIds();
		if (leads != null && leads.contains(user.getId())) return;
		if (user.getId().equals(project.getLeadId())) return; // legacy single lead
		throw ApiException.forbidden("error.project.notLead");
	}

	/**
	 * Validates and atomically commits a settings update. Only non-null request
	 * fields are touched. Enforces every invariant from the spec (name/key,
	 * >=1 lead, >=2 states, >=1 resolved) and cascades workflow/label renames and
	 * deletions to the affected issues so {@code Issue.state}/{@code Issue.tags}
	 * (which key off names) stay consistent.
	 */
	public Project applyUpdate(String id, ProjectUpdateRequest req, User user) {
		Project project = get(id);
		assertLeadOrAdmin(project, user);

		if (req.name() != null) {
			if (req.name().isBlank()) throw ApiException.badRequest("error.project.nameRequired");
			project.setName(req.name().trim());
		}
		if (req.description() != null) project.setDescription(req.description());
		if (req.color() != null) project.setColor(req.color());
		if (req.archived() != null) project.setArchived(req.archived());

		String previousKey = project.getKey();
		// Snapshot members before the wholesale replace so we can notify only the
		// newly-added ones after save (mirrors TeamService.addMembers' diff).
		Set<String> previousMembers = new HashSet<>(project.getMemberIds());
		applyKey(project, req.key());
		applyMembersAndLeads(project, req);
		List<RenameOp> stateRenames = applyWorkflow(project, req);
		List<RenameOp> labelRenames = applyLabels(project, req);

		// Persist the project first so a cascade failure can't leave issues
		// pointing at a name the project no longer advertises.
		Project saved = projects.save(project);
		// A key change must re-key every issue's denormalized readableId (and the
		// git dev-info that mirrors it), or issues keep the old prefix (HN2-1).
		if (previousKey != null && !previousKey.equalsIgnoreCase(saved.getKey())) {
			reKeyIssues(id, saved.getKey());
		}
		stateRenames.forEach(r -> cascadeStateRename(id, r.from(), r.to()));
		labelRenames.forEach(r -> cascadeTagRename(id, r.from(), r.to()));
		// A workflow / resolved-states change can leave issues with a stale
		// resolvedAt (e.g. a once-resolved state is no longer resolved). Recompute
		// it so "done" stays truthful (struck-through sub-tasks, throughput, etc.).
		reconcileResolvedAt(saved);
		notifyNewMembers(saved, previousMembers, user);
		return saved;
	}

	/**
	 * Notifies each member added by this update (in-app + e-mail + push), except
	 * the actor who performed it. Best-effort: a notification failure must never
	 * fail the settings save.
	 */
	private void notifyNewMembers(Project saved, Set<String> previousMembers, User actor) {
		try {
			for (String userId : saved.getMemberIds()) {
				if (userId == null || previousMembers.contains(userId)
						|| userId.equals(actor.getId())) {
					continue;
				}
				notifications.notifyAddedToProject(userId, saved.getId(), saved.getName());
			}
		}
		catch (Exception ex) {
			log.warn("Notifying new members of project {} failed: {}", saved.getId(), ex.getMessage());
		}
	}

	/**
	 * Recomputes {@code resolvedAt} for every issue in the project from the
	 * current {@code resolvedStates}: sets it (now) where the state is resolved
	 * but the flag is missing, and clears it where the state is not resolved.
	 */
	private void reconcileResolvedAt(Project project) {
		List<String> resolved = project.getResolvedStates();
		// Mark issues now in a resolved state that lack a timestamp.
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(project.getId())
						.and(STATE).in(resolved).and(RESOLVED_AT).is(null)),
				new Update().set(RESOLVED_AT, Instant.now()), ISSUES);
		// Clear the flag on issues whose state is no longer resolved.
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(project.getId())
						.and(STATE).nin(resolved).and(RESOLVED_AT).ne(null)),
				new Update().unset(RESOLVED_AT), ISSUES);
	}

	private void applyKey(Project project, String rawKey) {
		if (rawKey == null) return;
		String key = rawKey.trim().toUpperCase(Locale.ROOT);
		if (!key.matches("[A-Z][A-Z0-9]{1,9}")) {
			throw ApiException.badRequest("error.project.invalidKey");
		}
		if (!key.equalsIgnoreCase(project.getKey())) {
			projects.findByKeyIgnoreCase(key).ifPresent(other -> {
				if (!other.getId().equals(project.getId())) {
					throw ApiException.conflict("error.project.keyExists");
				}
			});
		}
		project.setKey(key);
	}

	private void applyMembersAndLeads(Project project, ProjectUpdateRequest req) {
		List<String> members = req.memberIds() != null
				? new ArrayList<>(new LinkedHashSet<>(req.memberIds()))
				: new ArrayList<>(project.getMemberIds());
		List<String> leads;
		if (req.leadIds() != null) {
			leads = new ArrayList<>(new LinkedHashSet<>(req.leadIds()));
		} else if (req.leadId() != null) {
			leads = new ArrayList<>(List.of(req.leadId()));
		} else {
			leads = new ArrayList<>(project.getLeadIds() != null && !project.getLeadIds().isEmpty()
					? project.getLeadIds()
					: (project.getLeadId() != null ? List.of(project.getLeadId()) : List.of()));
		}
		leads.removeIf(l -> l == null || l.isBlank());
		if (leads.isEmpty()) throw ApiException.badRequest("error.project.leadRequired");
		// Every lead is implicitly a member.
		for (String lead : leads) {
			if (!members.contains(lead)) members.add(lead);
		}
		project.setMemberIds(members);
		project.setLeadIds(leads);
		project.setLeadId(leads.get(0));
	}

	/** Validates the new workflow, assigns ids to new states, and returns the
	 * renames (old name -> new name) detected by matching stable ids. */
	private List<RenameOp> applyWorkflow(Project project, ProjectUpdateRequest req) {
		List<RenameOp> renames = new ArrayList<>();
		List<Project.WorkflowState> incoming = req.workflowStates();
		if (incoming != null) {
			if (incoming.size() < 2) throw ApiException.badRequest("error.project.minStates");
			Set<String> seen = new HashSet<>();
			Map<String, String> oldNameById = new HashMap<>();
			for (Project.WorkflowState s : project.getWorkflowStates()) {
				if (s.getId() != null) oldNameById.put(s.getId(), s.getName());
			}
			for (Project.WorkflowState s : incoming) {
				if (s.getName() == null || s.getName().isBlank()) {
					throw ApiException.badRequest("error.project.stateNameRequired");
				}
				s.setName(s.getName().trim());
				if (!seen.add(s.getName().toLowerCase(Locale.ROOT))) {
					throw ApiException.badRequest("error.project.duplicateState", s.getName());
				}
				if (s.getId() == null || s.getId().isBlank()) {
					s.setId(Project.newId());
				} else {
					String oldName = oldNameById.get(s.getId());
					if (oldName != null && !oldName.equals(s.getName())) {
						renames.add(new RenameOp(oldName, s.getName()));
					}
				}
			}
			renames.addAll(resolveDeletions(project, incoming, oldNameById, req.stateMigrations()));
			project.setWorkflowStates(incoming);
		}

		// Resolve the new set of valid state names and reconcile resolvedStates.
		Set<String> stateNames = new HashSet<>(project.workflowStateNames());
		List<String> resolved = req.resolvedStates() != null
				? new ArrayList<>(req.resolvedStates())
				: new ArrayList<>(project.getResolvedStates());
		// Carry resolved flags across renames.
		for (RenameOp r : renames) {
			resolved.replaceAll(name -> name.equals(r.from()) ? r.to() : name);
		}
		resolved.removeIf(name -> !stateNames.contains(name));
		List<String> deduped = new ArrayList<>(new LinkedHashSet<>(resolved));
		if (deduped.isEmpty()) throw ApiException.badRequest("error.project.minResolved");
		project.setResolvedStates(deduped);
		return renames;
	}

	/**
	 * Handles workflow states that the update removes. A state with no issues is
	 * simply dropped; a state that still has issues assigned may NOT be deleted
	 * outright — the request must name a migration target (another surviving
	 * state) so those issues are moved there first. Returns the reassignments to
	 * cascade (old state name -> target's final name).
	 */
	private List<RenameOp> resolveDeletions(Project project, List<Project.WorkflowState> incoming,
			Map<String, String> oldNameById, Map<String, String> migrations) {
		Map<String, Project.WorkflowState> incomingById = incoming.stream()
				.collect(Collectors.toMap(Project.WorkflowState::getId, s -> s, (a, b) -> a));
		Map<String, String> moves = migrations != null ? migrations : Map.of();
		List<RenameOp> ops = new ArrayList<>();
		for (Map.Entry<String, String> entry : oldNameById.entrySet()) {
			String deletedId = entry.getKey();
			if (incomingById.containsKey(deletedId)) continue; // still present
			String oldName = entry.getValue();
			long count = countIssuesInState(project.getId(), oldName);
			if (count == 0) continue; // safe to drop
			String targetId = moves.get(deletedId);
			if (targetId == null) {
				throw ApiException.badRequest("error.project.stateHasIssues", oldName, count);
			}
			Project.WorkflowState target = incomingById.get(targetId);
			if (target == null) {
				throw ApiException.badRequest("error.project.invalidMigrationTarget");
			}
			ops.add(new RenameOp(oldName, target.getName()));
		}
		return ops;
	}

	private long countIssuesInState(String projectId, String stateName) {
		return mongo.count(
				new Query(Criteria.where(PROJECT_ID).is(projectId).and(STATE).is(stateName)),
				ISSUES);
	}

	/** Issue count per current workflow-state name — drives the settings UI's
	 * "this status still has N issues" guard before a delete. */
	public Map<String, Long> stateUsage(Project project) {
		Map<String, Long> usage = new LinkedHashMap<>();
		for (String name : project.workflowStateNames()) {
			usage.put(name, countIssuesInState(project.getId(), name));
		}
		return usage;
	}

	/** Validates labels, assigns ids to new ones, and returns label renames.
	 * Deleted labels are pulled from every issue's tags. */
	private List<RenameOp> applyLabels(Project project, ProjectUpdateRequest req) {
		List<RenameOp> renames = new ArrayList<>();
		List<Project.Label> incoming = req.labels();
		if (incoming == null) return renames;

		Map<String, String> oldNameById = new HashMap<>();
		Set<String> oldNames = new HashSet<>(project.labelNames());
		for (Project.Label l : project.getLabels()) {
			if (l.getId() != null) oldNameById.put(l.getId(), l.getName());
		}
		Set<String> seen = new HashSet<>();
		Set<String> newNames = new HashSet<>();
		for (Project.Label l : incoming) {
			if (l.getName() == null || l.getName().isBlank()) {
				throw ApiException.badRequest("error.project.labelNameRequired");
			}
			l.setName(l.getName().trim());
			if (!seen.add(l.getName().toLowerCase(Locale.ROOT))) {
				throw ApiException.badRequest("error.project.duplicateLabel", l.getName());
			}
			newNames.add(l.getName());
			if (l.getId() == null || l.getId().isBlank()) {
				l.setId(Project.newId());
			} else {
				String oldName = oldNameById.get(l.getId());
				if (oldName != null && !oldName.equals(l.getName())) {
					renames.add(new RenameOp(oldName, l.getName()));
				}
			}
		}
		project.setLabels(incoming);
		// Labels removed entirely (not renamed) get pulled from issues.
		Set<String> renamedFrom = new HashSet<>();
		renames.forEach(r -> renamedFrom.add(r.from()));
		for (String old : oldNames) {
			if (!newNames.contains(old) && !renamedFrom.contains(old)) {
				cascadeTagDelete(project.getId(), old);
			}
		}
		return renames;
	}

	/**
	 * Re-keys every issue in the project so its denormalized {@code readableId}
	 * reads {@code <key>-<numberInProject>}, and rewrites the matching
	 * {@code git_dev_info.issueKey} (which mirrors the readableId) so the
	 * development-info linkage survives. Per-issue writes only happen where the
	 * value actually differs, so this is idempotent and cheap to re-run — the
	 * boot migration calls it to repair projects renamed before this cascade
	 * existed. Returns the number of issues rewritten.
	 */
	public long reKeyIssues(String projectId, String key) {
		long changed = 0;
		var issues = mongo.getCollection(ISSUES);
		for (org.bson.Document doc : issues.find(new org.bson.Document(PROJECT_ID, projectId))) {
			String number = issueNumber(doc);
			if (number == null) continue;
			String readableId = key + "-" + number;
			if (!readableId.equals(doc.getString(READABLE_ID))) {
				issues.updateOne(new org.bson.Document("_id", doc.get("_id")),
						new org.bson.Document("$set", new org.bson.Document(READABLE_ID, readableId)));
				changed++;
			}
		}
		var devInfo = mongo.getCollection(GIT_DEV_INFO);
		for (org.bson.Document doc : devInfo.find(new org.bson.Document(PROJECT_ID, projectId))) {
			String issueKey = rePrefix(doc.getString(ISSUE_KEY), key);
			if (issueKey != null && !issueKey.equals(doc.getString(ISSUE_KEY))) {
				devInfo.updateOne(new org.bson.Document("_id", doc.get("_id")),
						new org.bson.Document("$set", new org.bson.Document(ISSUE_KEY, issueKey)));
			}
		}
		return changed;
	}

	/** Project-scoped issue number, from the authoritative counter or the id suffix. */
	private static String issueNumber(org.bson.Document issue) {
		Object number = issue.get("numberInProject");
		if (number instanceof Number n) return Long.toString(n.longValue());
		return numberSuffix(issue.getString(READABLE_ID));
	}

	/** Rewrites the "KEY-" prefix of a readable id / issue key, keeping the number. */
	private static String rePrefix(String value, String key) {
		String number = numberSuffix(value);
		return number == null ? null : key + "-" + number;
	}

	/** The digits after the first "-" of a readable id (e.g. "HN2-14" -&gt; "14"). */
	private static String numberSuffix(String readableId) {
		if (readableId == null) return null;
		int dash = readableId.indexOf('-');
		return (dash >= 0 && dash + 1 < readableId.length()) ? readableId.substring(dash + 1) : null;
	}

	private void cascadeStateRename(String projectId, String from, String to) {
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(projectId).and(STATE).is(from)),
				new Update().set(STATE, to), ISSUES);
	}

	private void cascadeTagRename(String projectId, String from, String to) {
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(projectId).and("tags").is(from)),
				new Update().addToSet("tags", to), ISSUES);
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(projectId).and("tags").is(from)),
				new Update().pull("tags", from), ISSUES);
	}

	private void cascadeTagDelete(String projectId, String label) {
		mongo.updateMulti(
				new Query(Criteria.where(PROJECT_ID).is(projectId).and("tags").is(label)),
				new Update().pull("tags", label), ISSUES);
	}

	private record RenameOp(String from, String to) {
	}

	/** Ids of projects this user can reach through any team they belong to. */
	private Set<String> teamGrantedProjectIds(User user) {
		Set<String> granted = new HashSet<>();
		teams.findByMembersUserId(user.getId())
				.forEach(team -> granted.addAll(TeamAccess.grantedProjectIds(team, user.getId())));
		return granted;
	}
}
