package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.moderation.report.ContentReport;

/**
 * The kinds of thing a freeze can point at.
 *
 * <p>Deliberately not the same enum as {@link ContentReport.TargetType}, even
 * though the first five constants line up with it. A report names something a
 * person can point at in the UI; a freeze has to name everything that has to stop
 * being served, and those two sets differ by exactly one entry — {@link #OBJECT},
 * which no user ever reports and which is the only thing that can cover an inline
 * image. {@code ModerationWiringTest} pins the correspondence for the five that
 * do line up, so a new reportable kind that cannot be frozen fails there rather
 * than being discovered when one is filed.
 */
public enum FrozenTargetType {

	/** A whole issue: its title, description, activity trail and attachments. */
	ISSUE,

	/** One comment, including its voice blob when it has one. */
	COMMENT,

	/** A knowledge-base article. */
	ARTICLE,

	/** One attachment, which is a subdocument of an issue rather than a row. */
	ATTACHMENT,

	/**
	 * An account — its profile and its avatar.
	 *
	 * <p>Enforced on four surfaces: the three directory endpoints
	 * ({@code /api/v1/users}, {@code /users/by-ids}, {@code /users/search}), the
	 * {@code PEOPLE} category of global search, and {@code AvatarService.load}. The
	 * account's avatar object key is additionally resolved and frozen as its own
	 * {@link #OBJECT} row, so the bytes are refused at the storage chokepoint even
	 * if a new route reaches them another way.
	 *
	 * <p>It was almost deleted instead, and the case for keeping it is the avatar.
	 * {@code /api/v1/users/*&#47;avatar} is one of only two unauthenticated content
	 * routes in the product, and it does not check whether the account is active —
	 * so deactivating a user, which is otherwise the right answer to a person
	 * problem, leaves their uploaded image served to the open internet. That is a
	 * surface no other mechanism here closes.
	 *
	 * <p>What this is <b>not</b> is a suspension. It does not deactivate the account,
	 * revoke sessions or stop the person working; {@code ContentReportService} spells
	 * out why a report about a person deliberately does not freeze them, and that is
	 * unchanged — this constant is reachable only from the admin hand-freeze, for an
	 * account whose profile or avatar is itself the material.
	 */
	USER,

	/**
	 * A stored object, keyed by its storage key.
	 *
	 * <p>The constant that makes the whole mechanism work, and the one with no
	 * counterpart in {@link ContentReport.TargetType}. An inline image has no
	 * database row anywhere — {@code MediaService} says so in its own javadoc: it
	 * "is referenced by URL from arbitrary Markdown". Freezing the comment that
	 * embeds it does nothing to the bytes, because the reference lives inside the
	 * frozen body and the media route never asks who is calling. Avatars and the
	 * organisation logo are worse still: both are served unauthenticated.
	 *
	 * <p>A key here is what lets one guard at {@code StorageService.getObject}
	 * cover the media proxy, the avatar route, the logo route, attachment download,
	 * voice playback and the e-mail reply that would otherwise post the bytes back
	 * out to an external address.
	 */
	OBJECT
}
