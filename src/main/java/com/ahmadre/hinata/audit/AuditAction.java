package com.ahmadre.hinata.audit;

import static com.ahmadre.hinata.audit.AuditCategory.*;
import static com.ahmadre.hinata.audit.AuditSeverity.*;

/**
 * The catalogue of security-relevant events Hinata can record. Each constant
 * carries its {@link AuditCategory} and a default {@link AuditSeverity}, plus a
 * {@code defaultEnabled} flag that seeds the per-event toggle the first time an
 * admin opens the audit settings.
 *
 * <p>The enum <em>name</em> is the stable wire key used both in the persisted
 * {@code audit_log} documents and in the {@code audit.events} settings map, so
 * renaming a constant is a breaking change — add a new one instead.
 */
public enum AuditAction {

	// --- Authentication ------------------------------------------------------
	LOGIN_SUCCESS(AUTHENTICATION, INFO, true),
	LOGIN_FAILURE(AUTHENTICATION, WARNING, true),
	LOGIN_BLOCKED(AUTHENTICATION, WARNING, true),
	MFA_FAILURE(AUTHENTICATION, WARNING, true),
	SSO_LOGIN(AUTHENTICATION, INFO, true),
	SESSION_REVOKED(AUTHENTICATION, NOTICE, true),

	// --- Account (self-service) ---------------------------------------------
	USER_REGISTERED(ACCOUNT, INFO, true),
	EMAIL_VERIFIED(ACCOUNT, NOTICE, true),
	PASSWORD_CHANGED(ACCOUNT, NOTICE, true),
	PASSWORD_RESET_REQUESTED(ACCOUNT, NOTICE, true),
	PASSWORD_RESET_COMPLETED(ACCOUNT, NOTICE, true),
	EMAIL_CHANGE_REQUESTED(ACCOUNT, NOTICE, true),
	EMAIL_CHANGED(ACCOUNT, NOTICE, true),
	TWO_FACTOR_ENABLED(ACCOUNT, NOTICE, true),
	TWO_FACTOR_DISABLED(ACCOUNT, WARNING, true),
	RECOVERY_CODES_REGENERATED(ACCOUNT, NOTICE, true),
	ACCOUNT_DELETED(ACCOUNT, WARNING, true),

	// --- Administration (admin acting on other users) ------------------------
	USER_INVITED(ADMINISTRATION, INFO, true),
	USER_CREATED(ADMINISTRATION, NOTICE, true),
	USER_ROLE_CHANGED(ADMINISTRATION, WARNING, true),
	USER_ACTIVATED(ADMINISTRATION, NOTICE, true),
	USER_APPROVED(ADMINISTRATION, NOTICE, true),
	USER_DEACTIVATED(ADMINISTRATION, WARNING, true),
	USER_DELETED(ADMINISTRATION, WARNING, true),
	USER_PASSWORD_RESET_SENT(ADMINISTRATION, NOTICE, true),
	USER_SESSIONS_REVOKED(ADMINISTRATION, NOTICE, true),

	// --- Configuration -------------------------------------------------------
	SETTINGS_CHANGED(CONFIGURATION, WARNING, true),
	LEGAL_DOCUMENT_UPDATED(CONFIGURATION, WARNING, true),

	// --- Data / privacy ------------------------------------------------------
	DATA_EXPORT_REQUESTED(DATA, NOTICE, true),
	ISSUE_DELETED(DATA, WARNING, true),
	ISSUE_ARCHIVED(DATA, INFO, true),
	ISSUE_UNARCHIVED(DATA, INFO, true),

	// --- Integration (Personal Access Tokens + MCP writes) -------------------
	PAT_CREATED(INTEGRATION, NOTICE, true),
	PAT_REVOKED(INTEGRATION, NOTICE, true),
	PAT_DELETED(INTEGRATION, NOTICE, true),
	MCP_ISSUE_CREATED(INTEGRATION, INFO, true),
	MCP_ISSUE_UPDATED(INTEGRATION, INFO, true),
	MCP_COMMENT_ADDED(INTEGRATION, INFO, true),
	MCP_COMMENT_EDITED(INTEGRATION, INFO, true),
	MCP_COMMENT_DELETED(INTEGRATION, NOTICE, true),
	MCP_KB_CREATED(INTEGRATION, INFO, true),
	MCP_KB_UPDATED(INTEGRATION, INFO, true),
	MCP_KB_DELETED(INTEGRATION, NOTICE, true),
	MCP_WORK_LOGGED(INTEGRATION, INFO, true),
	MCP_WORK_DELETED(INTEGRATION, NOTICE, true),
	MCP_SPRINT_CREATED(INTEGRATION, INFO, true),
	MCP_SPRINT_UPDATED(INTEGRATION, INFO, true),
	MCP_SPRINT_STARTED(INTEGRATION, NOTICE, true),
	MCP_SPRINT_COMPLETED(INTEGRATION, NOTICE, true),
	MCP_OAUTH_AUTHORIZED(INTEGRATION, NOTICE, true);

	private final AuditCategory category;
	private final AuditSeverity defaultSeverity;
	private final boolean defaultEnabled;

	AuditAction(AuditCategory category, AuditSeverity defaultSeverity, boolean defaultEnabled) {
		this.category = category;
		this.defaultSeverity = defaultSeverity;
		this.defaultEnabled = defaultEnabled;
	}

	public AuditCategory category() {
		return category;
	}

	public AuditSeverity defaultSeverity() {
		return defaultSeverity;
	}

	public boolean defaultEnabled() {
		return defaultEnabled;
	}
}
