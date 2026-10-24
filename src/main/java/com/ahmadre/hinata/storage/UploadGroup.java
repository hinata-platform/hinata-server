package com.ahmadre.hinata.storage;

import java.util.Set;

/**
 * The groups of file types an instance can switch off for attachments, embedded images and voice
 * messages (HIN-177). Switching off only ever narrows the safe list; nothing here widens it.
 */
public enum UploadGroup {
	IMAGES(Set.of("image/png", "image/jpeg", "image/gif", "image/webp")),
	PDF(Set.of("application/pdf")),
	OFFICE(Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
	TEXT(Set.of("text/plain", "text/csv", "application/json")),
	ZIP(Set.of("application/zip")),
	VOICE(Set.of("audio/mp4", "audio/aac", "audio/x-m4a", "audio/m4a", "audio/mpeg", "audio/webm", "audio/ogg",
			"audio/wav", "audio/x-wav"));

	private final Set<String> types;

	UploadGroup(Set<String> types) {
		this.types = types;
	}

	public Set<String> types() {
		return types;
	}

	/** The group [contentType] belongs to; null for a type no group switches, such as a BMP avatar. */
	public static UploadGroup of(String contentType) {
		for (UploadGroup group : values()) {
			if (group.types.contains(contentType)) {
				return group;
			}
		}
		return null;
	}
}
