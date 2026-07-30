package com.ahmadre.hinata.project;

import com.ahmadre.hinata.team.TeamAccess;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
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
 */
@Component
@RequiredArgsConstructor
public class ProjectReach {

	private final ProjectRepository projects;
	// A repository, not TeamService: TeamService depends on the project domain.
	private final TeamRepository teams;

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

	/** Every project id {@code user} reaches through a team membership. */
	public Set<String> teamGrantedProjectIds(User user) {
		Set<String> granted = new HashSet<>();
		teams.findByMembersUserId(user.getId())
				.forEach(team -> granted.addAll(TeamAccess.grantedProjectIds(team, user.getId())));
		return granted;
	}
}
