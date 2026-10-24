package com.ahmadre.hinata.storage;

/**
 * Why the upload gate refused something (HIN-177): the reason as the details of the refusal name
 * it, and the sentence the person who sent it reads.
 */
public enum UploadRefusal {
	FILE_NAME("fileName", "error.upload.refused.fileName"),
	EMPTY("empty", "error.upload.refused.empty"),
	SIZE("size", "error.upload.refused.size"),
	UNREADABLE("unreadable", "error.upload.refused.unreadable"),
	TYPE("type", "error.upload.refused.type"),
	SIGNATURE("signature", "error.upload.refused.signature"),
	DISABLED("disabled", "error.upload.refused.disabled"),
	IMAGE("image", "error.upload.refused.image"),
	METADATA("metadata", "error.upload.refused.metadata");

	private final String code;
	private final String messageKey;

	UploadRefusal(String code, String messageKey) {
		this.code = code;
		this.messageKey = messageKey;
	}

	/** The reason in the refusal's details. */
	public String code() {
		return code;
	}

	/** The key of the sentence; only {@link #SIZE} takes an argument, the limit in MB. */
	public String messageKey() {
		return messageKey;
	}

	/** The refusal [code] names; {@link #TYPE} for a code this server does not know. */
	public static UploadRefusal of(String code) {
		for (UploadRefusal refusal : values()) {
			if (refusal.code.equals(code)) {
				return refusal;
			}
		}
		return TYPE;
	}
}
