package com.ahmadre.hinata.team;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Team groups {@link com.ahmadre.hinata.user.User}s (each with a per-team role)
 * and grants them access to {@link com.ahmadre.hinata.project.Project}s. It is an
 * organizational layer over the Project → Issue → Board model: a member's
 * {@link ProjectAccess} contributes to what projects they can see app-wide.
 */
@Data
@Builder
@Document("teams")
public class Team {

	@Id
	private String id;

	/** Short uppercase code, e.g. CORE. Unique across the workspace. */
	@Indexed(unique = true)
	private String key;

	@TextIndexed(weight = 10)
	private String name;

	@TextIndexed(weight = 2)
	private String description;

	/** oklch hue (see palette in the app), 0–360; the team's accent. */
	@Builder.Default
	private int colorHue = 70;

	/** lucide/Material icon name, e.g. "hexagon". */
	@Builder.Default
	private String icon = "hexagon";

	/** User id of the creator – becomes the first Team-Admin. */
	private String createdBy;

	/** Projects the team owns / shares. */
	@Builder.Default
	private List<String> projectIds = new ArrayList<>();

	@Builder.Default
	private List<TeamMembership> members = new ArrayList<>();

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/** The membership for [userId], or null if the user is not on the team. */
	public TeamMembership membership(String userId) {
		if (userId == null) return null;
		return members.stream().filter(m -> userId.equals(m.getUserId())).findFirst().orElse(null);
	}

	public boolean isMember(String userId) {
		return membership(userId) != null;
	}

	public boolean isAdmin(String userId) {
		TeamMembership m = membership(userId);
		return m != null && m.isAdmin();
	}

	public long adminCount() {
		return members.stream().filter(TeamMembership::isAdmin).count();
	}
}
