package com.ahmadre.hinata.content;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One stored object content consists of or embeds: an attachment, a voice message, an image. Where
 * it lies stays on the server: answers name the file, files come only through routes that check.
 */
public record MediaRef(@JsonIgnore String objectKey, String fileName, String contentType, long size) {

	public static MediaRef of(String objectKey) {
		return new MediaRef(objectKey, null, null, 0);
	}
}
