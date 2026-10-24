package com.ahmadre.hinata.moderation.quarantine;

/**
 * One stored object a removal copied.
 *
 * @param move         whether the original goes; false for an image other documents still show
 * @param verifiedGone whether the original was confirmed deleted
 */
public record QuarantinedObject(String originalKey, String quarantineKey, String fileName, String contentType,
		long size, boolean move, boolean verifiedGone) {

	QuarantinedObject gone() {
		return new QuarantinedObject(originalKey, quarantineKey, fileName, contentType, size, move, true);
	}
}
