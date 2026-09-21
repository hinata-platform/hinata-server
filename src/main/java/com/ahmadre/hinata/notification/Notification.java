package com.ahmadre.hinata.notification;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@Document("notifications")
// A person's latest target reminder, found and replaced before a new one is written (HIN-92).
// Partial, so it holds one entry per person with a target and not their whole history.
@CompoundIndex(name = "user_target_reminder", def = "{'userId': 1, 'type': 1}",
		partialFilter = "{'type': 'TIME_TARGET_REMINDER'}")
public class Notification {

	public enum Type {
		ISSUE_ASSIGNED, ISSUE_UPDATED, ISSUE_COMMENTED, ISSUE_INGESTED, ISSUE_DUE_SOON, MENTION,
		COMMENT_REPLY,
		SPRINT_STARTED, SPRINT_COMPLETED, DIGEST, SECURITY_ALERT, SYSTEM,
		TIME_TIMER_AUTO_STOPPED,
		TIMESHEET_SUBMITTED, TIMESHEET_APPROVED, TIMESHEET_REJECTED, TIMESHEET_REOPENED,
		TIME_CORRECTION_REQUESTED, TIME_CORRECTION_ANSWERED, TIME_BACKFILL_REQUESTED,
		TIME_TARGET_REMINDER, TIME_BUDGET_ALERT, TIME_ESTIMATE_REACHED,
		TIME_OFF_ENTITLEMENT_CHANGED,
		TIME_OFF_REQUESTED, TIME_OFF_APPROVED, TIME_OFF_REJECTED, TIME_OFF_AUTO_APPROVED,
		TIME_OFF_CANCELLED, TIME_OFF_SHORTENED, TIME_OFF_SUBSTITUTE_NAMED,
		TIME_REPORT_SCHEDULED,
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

	/** In-app deep link, e.g. /issues/ACME-42. */
	private String link;

	@Builder.Default
	private boolean read = false;

	@CreatedDate
	private Instant createdAt;
}
