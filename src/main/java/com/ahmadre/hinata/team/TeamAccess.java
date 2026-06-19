package com.ahmadre.hinata.team;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure (Spring-free) computation of which of a team's projects a user may see
 * through that team. Shared by {@link TeamService} (the Teams UI) and
 * {@code ProjectService} (app-wide project gating) so the rule lives in one
 * place and the two services don't form a bean cycle.
 *
 * <p>Team-Admins always see every project the team owns; otherwise the
 * membership's {@link ProjectAccess} decides (ALL / SOME / NONE). Returned ids
 * are always intersected with the team's current {@code projectIds}, so a stale
 * {@code SOME} entry for a detached project can never grant access.
 */
public final class TeamAccess {

	private TeamAccess() {
	}

	public static Set<String> grantedProjectIds(Team team, String userId) {
		TeamMembership membership = team.membership(userId);
		if (membership == null) return Collections.emptySet();

		Set<String> owned = new HashSet<>(team.getProjectIds());
		if (membership.isAdmin()) return owned;

		ProjectAccess access = membership.getAccess();
		if (access == null) return Collections.emptySet();
		return switch (access.getScope()) {
			case ALL -> owned;
			case NONE -> Collections.emptySet();
			case SOME -> {
				Set<String> granted = new HashSet<>(access.getProjectIds());
				granted.retainAll(owned);
				yield granted;
			}
		};
	}
}
