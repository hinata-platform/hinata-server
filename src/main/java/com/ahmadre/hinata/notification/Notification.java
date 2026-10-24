package com.ahmadre.hinata.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
// What waits for a person's notification window (HIN-131). Partial, so it holds only the
// few notifications that are waiting, not everybody's history.
@CompoundIndex(name = "held_user", def = "{'held': 1, 'userId': 1}", partialFilter = "{'held': true}")
// The bell, newest first a page at a time, off the index instead of sorting a person's whole history.
@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1, '_id': -1}")
// Every notification about one issue, found by its link when the issue's words are scrubbed (HIN-169).
@CompoundIndex(name = "link_id", def = "{'link': 1, '_id': 1}")
public class Notification {

	public enum Type {
		ISSUE_ASSIGNED, ISSUE_SHARED, ISSUE_UPDATED, ISSUE_COMMENTED, ISSUE_INGESTED, ISSUE_DUE_SOON, MENTION,
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
		TIME_OFF_EXPIRY_NOTICE, TIME_OFF_BALANCE_SUMMARY,
		TIME_ENTRY_SHARED, TIME_SHARE_ACCEPTED,
		ACCOUNT_ACTIVATED, ACCOUNT_DEACTIVATED, ACCOUNT_ROLE_CHANGED, ACCOUNT_DELETED,
		TEAM_ADDED, TEAM_ROLE_CHANGED, TEAM_REMOVED, PROJECT_ADDED,
		// Shift planning (HIN-46). The names are the wire contract; the module itself stays out of here.
		SHIFT_ASSIGNED, SHIFT_RELEASED, SHIFT_REMINDER, SHIFT_EVENT_CHANGED, SHIFT_CHANGED,
		SHIFT_HELPER_DECLINED,
		// HIN-161: a series changed or ended from an occurrence on; one notice per person.
		SHIFT_SERIES_CHANGED,
		// HIN-162: the waiting list. Moved up, passed over because of another shift, deadline missed.
		SHIFT_PROMOTED, SHIFT_WAITLIST_SKIPPED, SHIFT_WAITLIST_EXPIRED
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

	/**
	 * The comment or issue whose words this notification copies, so they can be scrubbed when
	 * it is removed or deleted (HIN-169). Absent on notifications written before; those are found
	 * by their link and their text.
	 */
	@JsonIgnore
	@Indexed(sparse = true)
	private String sourceId;

	@Builder.Default
	private boolean read = false;

	/**
	 * Written outside the person's notification window (HIN-131): the mail and push it would
	 * have sent wait for the window, where {@link HeldNotifications} sends everything that
	 * waited as one mail and one push. {@code held} is set whenever either channel waits.
	 */
	// Object types, not primitives: every notification written before these fields existed
	// comes back without them, and a primitive constructor parameter refuses the null, which
	// made the whole document unreadable. The getters below give the default.
	@JsonIgnore
	private Boolean held;

	@JsonIgnore
	private Boolean heldEmail;

	@JsonIgnore
	private Boolean heldPush;

	/** Never null: a notification written before HIN-131 never waited. */
	@JsonIgnore
	public boolean isHeld() {
		return Boolean.TRUE.equals(held);
	}

	@JsonIgnore
	public boolean isHeldEmail() {
		return Boolean.TRUE.equals(heldEmail);
	}

	@JsonIgnore
	public boolean isHeldPush() {
		return Boolean.TRUE.equals(heldPush);
	}

	@CreatedDate
	private Instant createdAt;
}
