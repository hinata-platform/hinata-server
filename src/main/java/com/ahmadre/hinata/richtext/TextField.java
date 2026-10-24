package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.content.ContentKind;

/**
 * The stored fields the text check reads (HIN-176), each with what it belongs to once saved and
 * whether it carries engineering prose. Code, logs and identifiers are taken out of those before
 * they are judged as language; a name is judged whole, or it would be a place to hide.
 */
public enum TextField {
	ISSUE_TITLE(ContentKind.ISSUE, true),
	ISSUE_DESCRIPTION(ContentKind.ISSUE, true),
	COMMENT(ContentKind.COMMENT, true),
	ARTICLE_TITLE(ContentKind.ARTICLE, true),
	ARTICLE_BODY(ContentKind.ARTICLE, true),
	DISPLAY_NAME(ContentKind.PERSON, false),
	POSITION(ContentKind.PERSON, false),
	PRONOUNS(ContentKind.PERSON, false),
	USERNAME(ContentKind.PERSON, false),
	/** Only refused, never flagged: after saving there is no content to look at. */
	OAUTH_CLIENT_NAME(null, false);

	private final ContentKind kind;
	private final boolean technical;

	TextField(ContentKind kind, boolean technical) {
		this.kind = kind;
		this.technical = technical;
	}

	/** What the field belongs to once saved; null when nothing can be looked at afterwards. */
	public ContentKind kind() {
		return kind;
	}

	/** Whether code, logs and identifiers in it are taken out before it is judged. */
	public boolean technical() {
		return technical;
	}

	/** Whether a find in it may go to moderation as a case. */
	public boolean flaggable() {
		return kind != null;
	}
}
