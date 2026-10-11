package com.ahmadre.hinata.audit;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which feed an audit action lands in, and whether its details name project content,
 * is decided by name (HIN-129). A new action has to be looked at once: the list of
 * reviewed actions below breaks the build until it is added, and the two expected
 * sets say where each one belongs. A time record left in the platform feed would show
 * an administrator who reported sick; a content record left unstripped would name a
 * project's issues.
 */
class AuditActionAudienceTest {

	private static final Set<String> REVIEWED = Set.of(
			"LOGIN_SUCCESS", "LOGIN_FAILURE", "LOGIN_BLOCKED", "MFA_FAILURE", "SSO_LOGIN",
			"SESSION_REVOKED", "USER_REGISTERED", "EMAIL_VERIFIED", "PASSWORD_CHANGED",
			"PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED", "EMAIL_CHANGE_REQUESTED",
			"EMAIL_CHANGED", "TWO_FACTOR_ENABLED", "TWO_FACTOR_DISABLED", "RECOVERY_CODES_REGENERATED",
			"ACCOUNT_DELETED", "USER_INVITED", "USER_CREATED", "USER_ROLE_CHANGED", "USER_ACTIVATED",
			"USER_APPROVED", "USER_DEACTIVATED", "USER_DELETED", "USER_PASSWORD_RESET_SENT",
			"USER_EMAIL_CHANGED", "USER_SESSIONS_REVOKED", "SETTINGS_CHANGED", "CONNECT_ENROLLED",
			"CONNECT_HANDSHAKE_STARTED", "CONNECT_DISCONNECTED", "DATA_EXPORT_REQUESTED", "ISSUE_DELETED",
			"ISSUE_ARCHIVED", "ISSUE_UNARCHIVED", "ISSUE_MOVED", "ISSUE_CLONED", "ISSUE_EXPORTED",
			"TIME_REPORT_EXPORTED", "TIME_ENTRIES_IMPORTED", "TIME_CALENDAR_SUBSCRIPTION_CREATED",
			"TIME_CALENDAR_SUBSCRIPTION_DELETED", "TIME_ENTRY_SHARED", "TIME_SHARE_ACCEPTED",
			"TIME_SHARE_REVOKED", "ISSUE_WATCHED", "ISSUE_UNWATCHED",
			"TIME_ENTRY_UPDATED", "TIME_ENTRY_DELETED", "TIME_ENTRY_CREATED_FOR", "TIME_ENTRY_CREATED",
			"TIME_TIMER_STARTED", "TIME_TIMER_STOPPED", "TIME_TIMER_DISCARDED", "TIMESHEET_SUBMITTED",
			"TIMESHEET_WITHDRAWN", "TIMESHEET_APPROVED", "TIMESHEET_REJECTED", "TIMESHEET_REOPENED",
			"TIME_CORRECTION_REQUESTED", "TIME_CORRECTION_ANSWERED", "TIME_BACKFILL_REQUESTED",
			"TIME_BACKFILL_GRANTED", "TIME_BACKFILL_REVOKED", "TIME_RETENTION_RUN", "TIME_POLICY_CHANGED",
			"TIME_LOCK_CHANGED", "TIME_TAG_CREATED", "TIME_TAG_UPDATED", "TIME_TAG_DELETED",
			"TIME_PROJECT_SETTINGS_CHANGED", "TIME_LOCK_EXCEPTION_ADDED", "TIME_LOCK_EXCEPTION_REMOVED",
			"AVAILABILITY_SCHEDULE_CHANGED", "AVAILABILITY_TIME_OFF_CHANGED",
			"AVAILABILITY_CALENDAR_CHANGED", "AVAILABILITY_HOLIDAYS_CHANGED",
			"AVAILABILITY_HOLIDAYS_IMPORTED", "TIME_OFF_TYPE_CHANGED", "TIME_OFF_ENTITLEMENT_CHANGED",
			"TIME_OFF_LEDGER_BOOKED", "TIME_OFF_EMPLOYMENT_CHANGED", "TIME_OFF_REQUEST_SUBMITTED",
			"TIME_OFF_REQUEST_APPROVED", "TIME_OFF_REQUEST_REJECTED", "TIME_OFF_REQUEST_WITHDRAWN",
			"TIME_OFF_REQUEST_EDITED", "TIME_OFF_REQUEST_CANCELLED", "TIME_OFF_SICK_REPORTED",
			"TIME_OFF_EXPIRY_NOTICE_SENT", "TIME_OFF_PROPOSAL_DECIDED", "TIME_OFF_REPORT_EXPORTED",
			"TIME_OFF_YEAR_RUN", "TIME_OFF_RETENTION_RUN", "BILLING_RATE_CREATED", "BILLING_RATE_UPDATED",
			"BILLING_RATE_DELETED", "INVOICE_CREATED", "INVOICE_DELETED", "INVOICE_ISSUED", "INVOICE_CREDITED",
			"INVOICE_EXPORTED", "PAT_CREATED", "PAT_REVOKED", "PAT_DELETED",
			"MCP_ISSUE_CREATED", "MCP_ISSUE_UPDATED", "MCP_COMMENT_ADDED", "MCP_COMMENT_EDITED",
			"MCP_COMMENT_DELETED", "MCP_KB_CREATED", "MCP_KB_UPDATED", "MCP_KB_DELETED", "MCP_WORK_LOGGED",
			"MCP_WORK_DELETED", "MCP_WORK_UPDATED", "MCP_TIMER_STARTED", "MCP_TIMER_STOPPED",
			"MCP_TIMER_DISCARDED", "MCP_SPRINT_CREATED", "MCP_SPRINT_UPDATED", "MCP_SPRINT_STARTED",
			"MCP_SPRINT_COMPLETED", "MCP_ATTACHMENT_READ", "MCP_OAUTH_AUTHORIZED",
			"PROJECT_TEMPLATES_POLICY_CHANGED", "PROJECT_SCHEDULE_SHIFTED", "PROJECT_COPIED",
			"PROJECT_TEMPLATE_MARKED", "PROJECT_INSTANTIATED", "ARTICLE_MOVED");

	private static final Set<String> CONTENT = Set.of("ISSUE_DELETED", "ISSUE_ARCHIVED", "ISSUE_UNARCHIVED",
			"ISSUE_MOVED", "ISSUE_CLONED", "ISSUE_EXPORTED", "ISSUE_WATCHED", "ISSUE_UNWATCHED",
			"MCP_ISSUE_CREATED", "MCP_ISSUE_UPDATED", "MCP_COMMENT_ADDED", "MCP_COMMENT_EDITED",
			"MCP_COMMENT_DELETED", "MCP_KB_CREATED", "MCP_KB_UPDATED", "MCP_KB_DELETED", "MCP_SPRINT_CREATED",
			"MCP_SPRINT_UPDATED", "MCP_SPRINT_STARTED", "MCP_SPRINT_COMPLETED", "MCP_ATTACHMENT_READ",
			"ARTICLE_MOVED", "PROJECT_SCHEDULE_SHIFTED", "PROJECT_COPIED", "PROJECT_INSTANTIATED");

	@Test
	void everyActionHasBeenPlaced() {
		assertThat(Arrays.stream(AuditAction.values()).map(Enum::name).collect(Collectors.toSet()))
				.as("a new audit action: decide whether it is the organisation's and whether it names content, "
						+ "then add it to REVIEWED")
				.isEqualTo(REVIEWED);
	}

	@Test
	void workingTimeTimesheetsAndAbsencesAreTheOrganisations() {
		for (AuditAction action : AuditAction.values()) {
			String name = action.name();
			boolean expected = name.startsWith("TIME_") || name.startsWith("TIMESHEET_")
					|| name.startsWith("AVAILABILITY_") || name.startsWith("BILLING_")
					|| name.startsWith("INVOICE_") || name.startsWith("MCP_WORK_")
					|| name.startsWith("MCP_TIMER_");
			assertThat(action.organisational()).as(name).isEqualTo(expected);
		}
	}

	@Test
	void recordsNamingProjectContentAreMarked() {
		for (AuditAction action : AuditAction.values()) {
			assertThat(action.content()).as(action.name()).isEqualTo(CONTENT.contains(action.name()));
		}
	}

	@Test
	void absenceRecordsAreASubsetOfTheOrganisations() {
		for (AuditAction action : AuditAction.values()) {
			if (action.absence()) {
				assertThat(action.organisational()).as(action.name()).isTrue();
			}
		}
		assertThat(AuditAction.TIME_OFF_SICK_REPORTED.absence()).isTrue();
		assertThat(AuditAction.AVAILABILITY_HOLIDAYS_IMPORTED.absence()).isFalse();
	}
}
