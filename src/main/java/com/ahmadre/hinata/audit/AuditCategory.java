package com.ahmadre.hinata.audit;

/**
 * Broad grouping of {@link AuditAction}s, used to filter the audit log and to
 * group the per-event toggles in the admin UI.
 */
public enum AuditCategory {

	/** Sign-in, sign-out, 2FA challenges, brute-force lockouts. */
	AUTHENTICATION,

	/** A user acting on their own account (password, e-mail, 2FA, deletion). */
	ACCOUNT,

	/** Privileged actions by an administrator against other users. */
	ADMINISTRATION,

	/** Runtime configuration changes to the platform itself. */
	CONFIGURATION,

	/** Data access / export with privacy relevance (GDPR). */
	DATA,

	/**
	 * External integrations acting on the workspace — Personal Access Token
	 * lifecycle and writes performed by AI clients over the MCP endpoint.
	 */
	INTEGRATION
}
