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

	/** An account — its profile and its avatar. */
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
