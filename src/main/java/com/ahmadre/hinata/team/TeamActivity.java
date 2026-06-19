package com.ahmadre.hinata.team;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One audit entry in a team's activity feed (shown on the Overview tab). */
@Data
@Builder
@Document("team_activity")
public class TeamActivity {

	public enum Verb {
		CREATED, UPDATED,
		ADDED_MEMBER, PROMOTED, DEMOTED, REMOVED_MEMBER,
		ATTACHED_PROJECT, CREATED_PROJECT, DETACHED_PROJECT
	}

	@Id
	private String id;

	@Indexed
	private String teamId;

	/** User id who performed the action. */
	private String actorId;

	private Verb verb;

	/** Human label of the affected object (a user's name, a project's name…). */
	private String objectLabel;

	/** Optional trailing detail, e.g. "to Team-Admin" or "with access to Core". */
	private String extra;

	@CreatedDate
	private Instant createdAt;
}
