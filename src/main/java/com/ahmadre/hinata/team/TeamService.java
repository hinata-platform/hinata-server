package com.ahmadre.hinata.team;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Team lifecycle, membership, project attachment and the team activity feed.
 * Mirrors {@link ProjectService} conventions (i18n {@link ApiException} keys,
 * thin Mongo access). Authority is scoped: a Team-Admin manages only their own
 * team; a platform admin can manage any team. The last-Team-Admin invariant is
 * enforced here, not just in the UI.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

	private final TeamRepository teams;
	private final TeamActivityRepository activity;
	private final ProjectService projects;
	private final NotificationService notifications;

	// --- Reads ---------------------------------------------------------------

	public Team get(String id) {
		return teams.findById(id).orElseThrow(() -> ApiException.notFound("team"));
	}

	public List<Team> visibleTo(User user) {
		return user.isAdmin() ? teams.findAll() : teams.findByMembersUserId(user.getId());
	}

	/** Platform admin OR a Team-Admin of this specific team. */
	public boolean canManage(Team team, User user) {
		return user.isAdmin() || team.isAdmin(user.getId());
	}

	public void assertVisible(Team team, User user) {
		if (!user.isAdmin() && !team.isMember(user.getId())) {
			throw ApiException.forbidden("error.team.notMember");
		}
	}

	public void assertManage(Team team, User user) {
		if (!canManage(team, user)) {
			throw ApiException.forbidden("error.team.notManager");
		}
	}

	/** All project ids the user can reach through the teams they belong to. */
	public Set<String> grantedProjectIds(User user) {
		Set<String> granted = new HashSet<>();
		for (Team team : teams.findByMembersUserId(user.getId())) {
			granted.addAll(TeamAccess.grantedProjectIds(team, user.getId()));
		}
		return granted;
	}

	public Page<TeamActivity> activity(String teamId, int page, int size) {
		return activity.findByTeamIdOrderByCreatedAtDesc(
				teamId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
	}

	// --- Team CRUD -----------------------------------------------------------

	public Team create(User creator, String name, String key, String description, int colorHue,
			String icon) {
		String normalized = normalizeKey(key);
		if (teams.existsByKeyIgnoreCase(normalized)) {
			throw ApiException.conflict("error.team.keyExists");
		}
		Team team = Team.builder()
				.key(normalized)
				.name(name)
				.description(description)
				.colorHue(colorHue)
				.icon(icon != null && !icon.isBlank() ? icon : "hexagon")
				.createdBy(creator.getId())
				.projectIds(new ArrayList<>())
				.members(new ArrayList<>(List.of(TeamMembership.builder()
						.userId(creator.getId())
						.role(TeamRole.ADMIN)
						.access(ProjectAccess.all())
						.build())))
				.build();
		Team saved = teams.save(team);
		logActivity(saved, creator, TeamActivity.Verb.CREATED, saved.getName(), null);
		return saved;
	}

	public Team update(Team team, User actor, String name, String description, String key,
			Integer colorHue, String icon) {
		if (name != null) team.setName(name);
		if (description != null) team.setDescription(description);
		if (key != null) {
			String normalized = normalizeKey(key);
			if (!normalized.equalsIgnoreCase(team.getKey()) && teams.existsByKeyIgnoreCase(normalized)) {
				throw ApiException.conflict("error.team.keyExists");
			}
			team.setKey(normalized);
		}
		if (colorHue != null) team.setColorHue(colorHue);
		if (icon != null && !icon.isBlank()) team.setIcon(icon);
		Team saved = teams.save(team);
		logActivity(saved, actor, TeamActivity.Verb.UPDATED, saved.getName(), null);
		return saved;
	}

	public void delete(Team team) {
		teams.delete(team);
		activity.deleteByTeamId(team.getId());
	}

	// --- Membership ----------------------------------------------------------

	public Team addMembers(Team team, User actor, List<String> userIds, TeamRole role,
			ProjectAccess access) {
		ProjectAccess normalized = normalizeAccess(team, access);
		Set<String> existing = new HashSet<>();
		team.getMembers().forEach(m -> existing.add(m.getUserId()));
		List<String> added = new ArrayList<>();
		for (String userId : userIds) {
			if (userId == null || existing.contains(userId)) continue;
			existing.add(userId);
			added.add(userId);
			team.getMembers().add(TeamMembership.builder()
					.userId(userId).role(role).access(copyAccess(normalized)).build());
		}
		if (added.isEmpty()) return team;
		Team saved = teams.save(team);
		syncProjectMembership(new HashSet<>(saved.getProjectIds()), new HashSet<>(added));
		for (String userId : added) {
			logActivity(saved, actor, TeamActivity.Verb.ADDED_MEMBER, userId, role.name());
			notifications.notifyAddedToTeam(userId, saved.getId(), saved.getName());
		}
		return saved;
	}

	public Team updateMembership(Team team, User actor, String userId, TeamRole role,
			ProjectAccess access) {
		TeamMembership membership = requireMember(team, userId);
		boolean roleChanged = role != null && role != membership.getRole();
		if (roleChanged && membership.isAdmin() && role == TeamRole.MEMBER && team.adminCount() <= 1) {
			throw ApiException.conflict("error.team.lastAdmin");
		}
		if (role != null) membership.setRole(role);
		if (access != null) membership.setAccess(normalizeAccess(team, access));
		Team saved = teams.save(team);
		// Role/access changes can grant or revoke project visibility for this user.
		syncProjectMembership(new HashSet<>(saved.getProjectIds()), Set.of(userId));
		if (roleChanged) {
			logActivity(saved, actor,
					role == TeamRole.ADMIN ? TeamActivity.Verb.PROMOTED : TeamActivity.Verb.DEMOTED,
					userId, null);
			notifications.notifyTeamRoleChanged(userId, saved.getId(), saved.getName(),
					role == TeamRole.ADMIN);
		}
		return saved;
	}

	public Team removeMember(Team team, User actor, String userId) {
		TeamMembership membership = requireMember(team, userId);
		if (membership.isAdmin() && team.adminCount() <= 1) {
			throw ApiException.conflict("error.team.lastAdmin");
		}
		team.getMembers().removeIf(m -> userId.equals(m.getUserId()));
		Team saved = teams.save(team);
		// The removed user loses any project access this team granted them.
		syncProjectMembership(new HashSet<>(saved.getProjectIds()), Set.of(userId));
		logActivity(saved, actor, TeamActivity.Verb.REMOVED_MEMBER, userId, null);
		// Self-leave needs no "you were removed" notice for the actor.
		if (!userId.equals(actor.getId())) {
			notifications.notifyRemovedFromTeam(userId, saved.getName());
		}
		return saved;
	}

	// --- Projects ------------------------------------------------------------

	public Team attachProjects(Team team, User actor, List<String> projectIds) {
		List<Project> attached = new ArrayList<>();
		for (String projectId : projectIds) {
			if (projectId == null || team.getProjectIds().contains(projectId)) continue;
			Project project = projects.get(projectId); // 404 if it doesn't exist
			team.getProjectIds().add(projectId);
			attached.add(project);
		}
		if (attached.isEmpty()) return team;
		Team saved = teams.save(team);
		// Members with ALL access (and Team-Admins) immediately gain the new project.
		syncProjectMembership(
				attached.stream().map(Project::getId).collect(Collectors.toSet()), Set.of());
		for (Project project : attached) {
			logActivity(saved, actor, TeamActivity.Verb.ATTACHED_PROJECT, project.getName(), null);
		}
		return saved;
	}

	public Team detachProject(Team team, User actor, String projectId) {
		if (!team.getProjectIds().contains(projectId)) {
			throw ApiException.notFound("project");
		}
		// Resolve the label before mutating; the project itself is not deleted.
		String label = projects.findOptional(projectId).map(Project::getName).orElse(projectId);
		team.getProjectIds().remove(projectId);
		// Strip the detached project from every member's explicit access list so a
		// stale id can never linger (and can't be re-granted on a future attach).
		for (TeamMembership m : team.getMembers()) {
			ProjectAccess access = m.getAccess();
			if (access != null && access.getScope() == ProjectAccess.Scope.SOME) {
				access.getProjectIds().remove(projectId);
			}
		}
		Team saved = teams.save(team);
		// Drop every member this team had put on the project, unless they still
		// reach it through another team or are a project lead.
		Set<String> formerMembers =
				team.getMembers().stream().map(TeamMembership::getUserId).collect(Collectors.toSet());
		syncProjectMembership(Set.of(projectId), formerMembers);
		logActivity(saved, actor, TeamActivity.Verb.DETACHED_PROJECT, label, null);
		return saved;
	}

	public Project createTeamProject(Team team, User actor, String key, String name,
			String description, String color, String leadId) {
		Project project = Project.builder()
				.key(key)
				.name(name)
				.description(description)
				.color(color != null && !color.isBlank() ? color : "#AEC6F4")
				.leadId(leadId)
				.build();
		Project created = projects.create(project, actor); // validates key, seeds workflow
		team.getProjectIds().add(created.getId());
		Team saved = teams.save(team);
		// Seed the new project with everyone who already has team-wide access.
		syncProjectMembership(Set.of(created.getId()), Set.of());
		logActivity(saved, actor, TeamActivity.Verb.CREATED_PROJECT, created.getName(), null);
		return created;
	}

	// --- Helpers -------------------------------------------------------------

	private TeamMembership requireMember(Team team, String userId) {
		TeamMembership membership = team.membership(userId);
		if (membership == null) throw ApiException.notFound("user");
		return membership;
	}

	/**
	 * Materializes team grants into real project membership so a granted user
	 * appears in (and can work on) the project itself — not just pass the access
	 * gate. For each project id, every user currently granted it through <em>any</em>
	 * team is ensured present in {@code memberIds}; each [removalCandidate] who no
	 * longer reaches it through any team is dropped (project leads are always kept).
	 * Recomputing from the live teams keeps grant/revoke exact and idempotent.
	 */
	private void syncProjectMembership(Set<String> projectIds, Set<String> removalCandidates) {
		for (String projectId : projectIds) {
			projects.findOptional(projectId)
					.ifPresent(project -> reconcileProjectMembers(project, removalCandidates));
		}
	}

	private void reconcileProjectMembers(Project project, Set<String> removalCandidates) {
		Set<String> grantedNow = teamGrantedMembers(project.getId());
		List<String> members = project.getMemberIds();
		boolean changed = members.addAll(
				grantedNow.stream().filter(id -> !members.contains(id)).toList());
		for (String userId : removalCandidates) {
			if (grantedNow.contains(userId) || isProjectLead(project, userId)) continue;
			changed |= members.remove(userId);
		}
		if (changed) projects.save(project);
	}

	/** Every user who reaches [projectId] through any team that owns it. */
	private Set<String> teamGrantedMembers(String projectId) {
		Set<String> granted = new HashSet<>();
		for (Team owner : teams.findByProjectIdsContains(projectId)) {
			for (TeamMembership m : owner.getMembers()) {
				if (TeamAccess.grantedProjectIds(owner, m.getUserId()).contains(projectId)) {
					granted.add(m.getUserId());
				}
			}
		}
		return granted;
	}

	private boolean isProjectLead(Project project, String userId) {
		if (userId.equals(project.getLeadId())) return true;
		return project.getLeadIds() != null && project.getLeadIds().contains(userId);
	}

	/** Validates & cleans an access spec against the team's current projects. */
	private ProjectAccess normalizeAccess(Team team, ProjectAccess access) {
		if (access == null || access.getScope() == null) return ProjectAccess.none();
		return switch (access.getScope()) {
			case ALL -> ProjectAccess.all();
			case NONE -> ProjectAccess.none();
			case SOME -> {
				List<String> ids = access.getProjectIds() == null ? List.of() : access.getProjectIds();
				List<String> cleaned = ids.stream().distinct()
						.filter(id -> team.getProjectIds().contains(id)).toList();
				if (cleaned.size() != new HashSet<>(ids).size()) {
					throw ApiException.badRequest("error.team.accessNotSubset");
				}
				yield ProjectAccess.some(cleaned);
			}
		};
	}

	private ProjectAccess copyAccess(ProjectAccess access) {
		return switch (access.getScope()) {
			case ALL -> ProjectAccess.all();
			case NONE -> ProjectAccess.none();
			case SOME -> ProjectAccess.some(access.getProjectIds());
		};
	}

	private String normalizeKey(String key) {
		String normalized = key == null ? "" : key.toUpperCase(Locale.ROOT);
		if (!normalized.matches("[A-Z][A-Z0-9]{1,9}")) {
			throw ApiException.badRequest("error.team.invalidKey");
		}
		return normalized;
	}

	private void logActivity(Team team, User actor, TeamActivity.Verb verb, String objectLabel,
			String extra) {
		activity.save(TeamActivity.builder()
				.teamId(team.getId())
				.actorId(actor.getId())
				.verb(verb)
				.objectLabel(objectLabel)
				.extra(extra)
				.build());
	}
}
