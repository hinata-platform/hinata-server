package com.ahmadre.hinata.notification;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@Document("notifications")
public class Notification {

	public enum Type {
		ISSUE_ASSIGNED, ISSUE_UPDATED, ISSUE_COMMENTED, ISSUE_INGESTED, ISSUE_DUE_SOON, MENTION,
		COMMENT_REPLY,
		SPRINT_STARTED, SPRINT_COMPLETED, DIGEST, SECURITY_ALERT, SYSTEM,
		ACCOUNT_ACTIVATED, ACCOUNT_DEACTIVATED, ACCOUNT_ROLE_CHANGED, ACCOUNT_DELETED,
		TEAM_ADDED, TEAM_ROLE_CHANGED, TEAM_REMOVED, PROJECT_ADDED
	}

	@Id
	private String id;

	@Indexed
	private String userId;

	private Type type;

	private String title;

	private String body;

	/** In-app deep link, e.g. /issues/ASTA-42. */
	private String link;

	@Builder.Default
	private boolean read = false;

	@CreatedDate
	private Instant createdAt;
}
