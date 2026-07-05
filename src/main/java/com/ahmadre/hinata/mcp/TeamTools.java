package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tools over teams. Gates on {@code teams:read} and delegates to
 * {@link TeamService}: a caller sees exactly the teams they are a member of
 * (admins see all), mirroring the app. Member entries expose the same public
 * directory fields as {@code search_users}, never per-member project access
 * details beyond the team role.
 */
@Service
@RequiredArgsConstructor
public class TeamTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final TeamService teamService;
	private final UserRepository users;

	@McpTool(name = "list_teams", title = "List teams",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the teams the caller is a member of, without member details "
					+ "(use get_team for those).")
	public List<TeamView> listTeams() {
		scopeGuard.require(Scopes.TEAMS_READ);
		User user = currentUser.require();
		return teamService.visibleTo(user).stream()
				.map(team -> TeamView.of(team, null))
				.toList();
	}

	@McpTool(name = "get_team", title = "Get a team",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Fetch a team by id, including its projects and members (with team "
					+ "role and display name). Fails if the caller is not a member of the team.")
	public TeamView getTeam(
			@McpToolParam(description = "Team id") String teamId) {
		scopeGuard.require(Scopes.TEAMS_READ);
		User user = currentUser.require();
		Team team = teamService.get(teamId);
		teamService.assertVisible(team, user);
		Map<String, String> names = new HashMap<>();
		users.findAllById(team.getMembers().stream()
				.map(com.ahmadre.hinata.team.TeamMembership::getUserId).toList())
				.forEach(member -> names.put(member.getId(), member.getDisplayName()));
		List<TeamMemberView> members = team.getMembers().stream()
				.map(m -> new TeamMemberView(m.getUserId(), names.get(m.getUserId()),
						m.getRole() == null ? null : m.getRole().name()))
				.toList();
		return TeamView.of(team, members);
	}

	/** A team without internal access-grant details; members only on get_team. */
	public record TeamView(String id, String key, String name, String description,
			List<String> projectIds, int memberCount, List<TeamMemberView> members,
			Instant createdAt) {

		static TeamView of(Team team, List<TeamMemberView> members) {
			return new TeamView(team.getId(), team.getKey(), team.getName(),
					team.getDescription(), team.getProjectIds(),
					team.getMembers() == null ? 0 : team.getMembers().size(),
					members, team.getCreatedAt());
		}
	}

	/** One team member: user id, display name and team role (ADMIN or MEMBER). */
	public record TeamMemberView(String userId, String displayName, String role) {
	}
}
