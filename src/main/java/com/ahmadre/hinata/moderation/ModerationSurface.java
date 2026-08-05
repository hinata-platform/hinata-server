package com.ahmadre.hinata.moderation;

/**
 * Where a piece of content entered the system.
 *
 * <p>The surface is not decoration on a log line: it selects how strictly the
 * content is judged. Two properties drive that.
 *
 * <p>{@link #technical()} says whether this surface normally carries engineering
 * prose. An issue description is full of stack traces, shell commands and CVE
 * links, so it goes through {@link com.ahmadre.hinata.moderation.text.TechnicalTextStripper}
 * and gets a raised violence threshold; a display name or a project name is never
 * a stack trace, so stripping it would only create a hole to hide in.
 *
 * <p>{@link #external()} says whether the author is someone the organisation has
 * authenticated. Almost everything in Hinata is written by a colleague who signed
 * in — which is exactly why the risk concentrates in the handful of surfaces where
 * that is not true, above all inbound e-mail, where anyone who learns the ingest
 * address can put content in front of the whole team.
 */
public enum ModerationSurface {

	/** Issue summary/title — short, human-written, but shown in lists and push. */
	ISSUE_TITLE(true, false),
	/** Issue description — the most code-dense surface in the product. */
	ISSUE_DESCRIPTION(true, false),
	/** Threaded comment body. */
	COMMENT(true, false),
	/** Knowledge-base article title. */
	ARTICLE_TITLE(true, false),
	/** Knowledge-base article body. */
	ARTICLE_CONTENT(true, false),
	/** Worklog / time-tracking note. */
	WORKLOG(true, false),

	/** Project, team, board, sprint, space and label names — never technical prose. */
	ENTITY_NAME(false, false),
	/** Project/team/space descriptions and sprint goals. */
	ENTITY_DESCRIPTION(false, false),
	/** A person's own display name and job title. */
	PROFILE(false, false),
	/** Free-text note attached to an invitation e-mail. */
	INVITE_MESSAGE(false, false),

	/** File attached to an issue. */
	ATTACHMENT(false, false),
	/** Image pasted or inserted into the rich-text editor. */
	INLINE_IMAGE(false, false),
	/** A user's avatar. */
	AVATAR(false, false),
	/** The organisation logo (admin-uploaded). */
	ORGANISATION_LOGO(false, false),
	/** Recorded voice comment. */
	VOICE(true, false),

	/**
	 * External image URL fetched through the media proxy. Externally authored by
	 * definition — the bytes come from a host we do not control.
	 */
	EXTERNAL_IMAGE(false, true),
	/** Subject/body of a mail that became an issue. */
	EMAIL_INGEST(false, true),
	/** Attachment carried by an ingested mail. */
	EMAIL_ATTACHMENT(false, true),
	/** Reply e-mail composed in-app and sent to an external address. */
	EMAIL_REPLY(false, true),
	/** Commit message arriving via a Git webhook and landing as a comment. */
	GIT_COMMIT(true, true),
	/** Content authored through an MCP tool by an AI agent on a user's behalf. */
	MCP(true, false);

	private final boolean technical;
	private final boolean external;

	ModerationSurface(boolean technical, boolean external) {
		this.technical = technical;
		this.external = external;
	}

	/**
	 * Whether this surface normally carries code, logs and identifiers, and should
	 * therefore have them stripped before scoring.
	 */
	public boolean technical() {
		return technical;
	}

	/**
	 * Whether the author is outside the organisation's authenticated membership.
	 * External surfaces are judged one band stricter — see
	 * {@link ModerationPolicy#thresholdFor}.
	 */
	public boolean external() {
		return external;
	}

	/** Whether this surface carries bytes rather than text. */
	public boolean binary() {
		return switch (this) {
			case ATTACHMENT, INLINE_IMAGE, AVATAR, ORGANISATION_LOGO, VOICE,
					EXTERNAL_IMAGE, EMAIL_ATTACHMENT -> true;
			default -> false;
		};
	}
}
