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
	CONNECT_ENROLLED(CONFIGURATION, NOTICE, true),
	CONNECT_HANDSHAKE_STARTED(CONFIGURATION, INFO, true),
	CONNECT_DISCONNECTED(CONFIGURATION, WARNING, true),

	// --- Data / privacy ------------------------------------------------------
	DATA_EXPORT_REQUESTED(DATA, NOTICE, true),
	ISSUE_DELETED(DATA, WARNING, true),
	ISSUE_ARCHIVED(DATA, INFO, true),
	ISSUE_UNARCHIVED(DATA, INFO, true),
	ISSUE_MOVED(DATA, NOTICE, true),
	ISSUE_CLONED(DATA, INFO, true),
	ISSUE_EXPORTED(DATA, INFO, true),
	ISSUE_WATCHED(DATA, INFO, true),
	ISSUE_UNWATCHED(DATA, INFO, true),
	// A lead or admin changing or removing someone else's logged time. Editing
	// one's own entries is not audited — it is the ordinary use of the feature.
	TIME_ENTRY_UPDATED(DATA, NOTICE, true),
	TIME_ENTRY_DELETED(DATA, NOTICE, true),
	// Time booked onto somebody else's account: today only a smart commit does
	// this, crediting the commit author while the push is made by whoever holds
	// the repository. The author line of a commit is text anyone can write, so
	// the one thing that makes a wrong attribution findable afterwards is this
	// entry — actor is who pushed, target is who was credited.
	TIME_ENTRY_CREATED_FOR(DATA, NOTICE, true),
	// The four below record ordinary use of the module by the person it belongs
	// to: filing an entry, and starting, stopping or discarding one's own timer.
	// They are off by default and that is the whole point. A complete record of
	// when somebody started and stopped working is objectively suitable for
	// monitoring their behaviour (§ 87 Abs. 1 Nr. 6 BetrVG), so switching it on
	// is a decision the works parties make, not a default a deployment inherits.
	// The names exist regardless, because a name is a wire contract and adding it
	// later would date every record written before it.
	TIME_ENTRY_CREATED(DATA, INFO, false),
	TIME_TIMER_STARTED(DATA, INFO, false),
	TIME_TIMER_STOPPED(DATA, INFO, false),
	TIME_TIMER_DISCARDED(DATA, INFO, false),

	// --- Time-tracking configuration (policies, tags, per-project settings) ---
	// An operator changing what the module demands or freezes. On by default:
	// these are decisions *about* people rather than records of them, and a lock
	// date that moved without a trace is the one change nobody can reconstruct
	// afterwards.
	TIME_POLICY_CHANGED(CONFIGURATION, WARNING, true),
	TIME_LOCK_CHANGED(CONFIGURATION, WARNING, true),
	TIME_TAG_CREATED(CONFIGURATION, INFO, true),
	TIME_TAG_UPDATED(CONFIGURATION, NOTICE, true),
	TIME_TAG_DELETED(CONFIGURATION, NOTICE, true),
	TIME_PROJECT_SETTINGS_CHANGED(CONFIGURATION, NOTICE, true),

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
	MCP_ATTACHMENT_READ(INTEGRATION, INFO, true),
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
