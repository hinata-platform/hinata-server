package com.ahmadre.hinata.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * What projects of a team a member may see, embedded in {@link TeamMembership}.
 * <ul>
 *   <li>{@code ALL}  – every project the team owns.</li>
 *   <li>{@code NONE} – added to the team, but no project visibility yet.</li>
 *   <li>{@code SOME} – the explicit subset in {@link #projectIds} (always a
 *       subset of the team's {@code projectIds}).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAccess {

	public enum Scope { ALL, SOME, NONE }

	@Builder.Default
	private Scope scope = Scope.NONE;

	/** Only meaningful when {@link #scope} is {@code SOME}. */
	@Builder.Default
	private List<String> projectIds = new ArrayList<>();

	public static ProjectAccess all() {
		return ProjectAccess.builder().scope(Scope.ALL).build();
	}

	public static ProjectAccess none() {
		return ProjectAccess.builder().scope(Scope.NONE).build();
	}

	public static ProjectAccess some(List<String> projectIds) {
		return ProjectAccess.builder().scope(Scope.SOME)
				.projectIds(projectIds != null ? new ArrayList<>(projectIds) : new ArrayList<>())
				.build();
	}
}
