package com.ahmadre.hinata.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The join row embedded in a {@link Team}: it carries a user's role and the
 * project access granted to them inside that team. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMembership {

	private String userId;

	@Builder.Default
	private TeamRole role = TeamRole.MEMBER;

	@Builder.Default
	private ProjectAccess access = ProjectAccess.none();

	public boolean isAdmin() {
		return role == TeamRole.ADMIN;
	}
}
