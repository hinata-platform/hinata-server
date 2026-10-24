package com.ahmadre.hinata.content;

/**
 * Why something is reported, and what a decision names (HIN-26). In the core because the text
 * check names it too, not only the moderation module.
 */
public enum ReportCategory {
	HARASSMENT,
	THREAT,
	SEXUAL,
	CHILD_SAFETY,
	PRIVACY,
	SPAM,
	ILLEGAL,
	OTHER;

	/**
	 * Sexual content and anything that touches the safety of children: never shown as an excerpt,
	 * and an image removed for it is moved out of every document that embeds it, not copied.
	 */
	public boolean isSensitive() {
		return this == SEXUAL || this == CHILD_SAFETY;
	}
}
