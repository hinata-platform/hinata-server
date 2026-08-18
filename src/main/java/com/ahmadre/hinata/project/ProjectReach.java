package com.ahmadre.hinata.project;

import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamAccess;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one answer to "may this user see that project": platform admins see every
 * project, everyone else sees the projects they are a direct member of plus any project
 * granted to them through a team (see {@link TeamAccess}).
 *
 * <p>It lives apart from {@link ProjectService} because callers outside the project
 * domain need the rule without pulling in the whole service: {@code ProjectService}
 * depends on {@code NotificationService}, so a notification that wants to know whether a
 * recipient can reach a project cannot inject the service back without forming a bean
 * cycle. Reading from repositories only, this can be injected anywhere — and the rule
 * itself stays in exactly one place.
 *
 * <p>"One place" is meant literally, and it is why there is no id-only
 * {@code canSee(projectId, userId)}: a second implementation of the same rule is
 * how the answers start to diverge, and the divergence would sit in the path
 * every notification delivery runs through. Callers holding one {@link User} ask
 * {@link #canSee(String, User)}; callers holding a list of ids ask
 * {@link #whoCanSee(String, Collection)}. {@code ProjectReachTest} pins the two
 * to the same answers.
 */
@Component
@RequiredArgsConstructor
public class ProjectReach {

	private final ProjectRepository projects;
	// A repository, not TeamService: TeamService depends on the project domain.
	private final TeamRepository teams;
	// Likewise a repository, not UserService — whoCanSee is handed bare ids (a
	// watcher list, a queued digest entry) and has to resolve the accounts itself
	// to settle the platform-admin case.
	private final UserRepository users;

	/** Whether {@code user} may see the project — direct membership, team grant, or admin. */
	public boolean canSee(Project project, User user) {
		if (project == null || user == null) {
			return false;
		}
		if (user.isAdmin()) {
			return true;
		}
		if (project.getMemberIds() != null && project.getMemberIds().contains(user.getId())) {
			return true;
		}
		return teamGrantedProjectIds(user).contains(project.getId());
	}

	/**
	 * As {@link #canSee(Project, User)} by project id. A project that no longer exists
	 * is not visible to anyone — the deciding caller wants to know whether following a
	 * reference would land somewhere, and this one would not.
	 */
	public boolean canSee(String projectId, User user) {
		if (projectId == null || user == null) {
			return false;
		}
		return projects.findById(projectId).filter(project -> canSee(project, user)).isPresent();
	}

	/**
	 * The subset of {@code userIds} that may see one project.
	 *
	 * <p>Exists because the callers that need this are fanning out to a whole
	 * watcher list, where the project is the same for everyone: asking
	 * {@link #canSee(String, User)} per candidate would re-read that project and
	 * re-query the team collection once per watcher. Here it is one project read,
	 * then at most one team query, then at most one user read — and the cheap
	 * answers are settled first, so a list of ordinary project members never
	 * touches the team or user collections at all.
	 */
	public Set<String> whoCanSee(String projectId, Collection<String> userIds) {
		if (projectId == null || userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		Project project = projects.findById(projectId).orElse(null);
		// A project that no longer exists is visible to nobody — same rule as the
		// single-user overload above.
		if (project == null) {
			return Set.of();
		}
		Set<String> undecided = new LinkedHashSet<>(userIds);
		undecided.remove(null);
		Set<String> allowed = new HashSet<>();
		if (project.getMemberIds() != null) {
			for (String memberId : project.getMemberIds()) {
				if (undecided.remove(memberId)) allowed.add(memberId);
			}
		}
		if (!undecided.isEmpty()) {
			for (Team team : teams.findByProjectIdsContains(projectId)) {
				for (Iterator<String> it = undecided.iterator(); it.hasNext();) {
					String userId = it.next();
					if (TeamAccess.grantedProjectIds(team, userId).contains(projectId)) {
						allowed.add(userId);
						it.remove();
					}
				}
				if (undecided.isEmpty()) break;
			}
		}
		if (!undecided.isEmpty()) {
			// Whatever is left can still be a platform admin, who sees everything.
			users.findAllById(undecided).forEach(user -> {
				if (user.isAdmin()) allowed.add(user.getId());
			});
		}
		return allowed;
	}

	/** Every project id {@code user} reaches through a team membership. */
	public Set<String> teamGrantedProjectIds(User user) {
		Set<String> granted = new HashSet<>();
		teams.findByMembersUserId(user.getId())
				.forEach(team -> granted.addAll(TeamAccess.grantedProjectIds(team, user.getId())));
		return granted;
	}
}
