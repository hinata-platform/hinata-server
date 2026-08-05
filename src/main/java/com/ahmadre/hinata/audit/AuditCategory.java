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
	INTEGRATION,

	/**
	 * Content safety: reports filed about content or people, and the personal
	 * blocks users place on each other.
	 *
	 * <p>Its own category rather than a corner of {@link #DATA} because these are
	 * the events a store review or a DSA transparency question asks about, and
	 * answering "how many reports did you receive and what happened to them" must
	 * not mean filtering them back out of everything else that touches user data.
	 */
	MODERATION
}
