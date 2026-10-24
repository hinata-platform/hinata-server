package com.ahmadre.hinata.storage;

/** What bytes somebody wants to store are for (HIN-177); each kind has its own types and limits. */
public enum UploadKind {
	ATTACHMENT,
	/** An image uploaded into a text. */
	MEDIA,
	/**
	 * An image that arrived inside a text as {@code data:} bytes: media with fewer pixels, since
	 * one save brings up to twenty of them.
	 */
	PASTED_IMAGE,
	VOICE,
	AVATAR,
	LOGO,
	MAIL_ATTACHMENT;

	/** Whether readers see it inside a text, so it loses its metadata before it is stored. */
	public boolean shownInText() {
		return this == MEDIA || this == PASTED_IMAGE;
	}
}
