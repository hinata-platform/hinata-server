package com.ahmadre.hinata.content;

/** One stored object content consists of or embeds: an attachment, a voice message, an image. */
public record MediaRef(String objectKey, String fileName, String contentType, long size) {

	public static MediaRef of(String objectKey) {
		return new MediaRef(objectKey, null, null, 0);
	}
}
