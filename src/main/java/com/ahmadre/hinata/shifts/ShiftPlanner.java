package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Somebody who plans shifts in a project without leading it (HIN-45). The project's leads and the
 * admins of a team that owns it plan anyway; this is the role below them, and it lives here rather
 * than on {@code Project}, which carries nothing of the module.
 *
 * <p>The entry is cleared when the person loses the project ({@link ShiftErasure}), so a planner
 * right never comes back on its own when they rejoin: a lead has to grant it again. The field names
 * are read by name in {@link ShiftPersonalData}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_planners")
// One entry per person and project, and "does this person plan here" as an equality lookup. Also
// the planner list of a project (projectId the equality).
@CompoundIndex(name = "project_user", def = "{'projectId': 1, 'userId': 1}", unique = true)
// A person's entries: which of a set of projects they plan ({@code plannableProjectIds}, userId the
// equality, projectId the $in), their data export and the erasure of their account.
@CompoundIndex(name = "user_project", def = "{'userId': 1, 'projectId': 1}")
public class ShiftPlanner {

	@Id
	private String id;

	private String projectId;

	private String userId;

	/** The lead or team admin who granted the role. */
	private String grantedBy;

	private Instant createdAt;
}
