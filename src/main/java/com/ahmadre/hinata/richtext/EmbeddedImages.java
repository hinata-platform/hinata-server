package com.ahmadre.hinata.richtext;

import java.util.Optional;

/**
 * Where the images that arrive inside a text are kept (HIN-177): this server's media, behind the
 * upload gate. A port, so that rich text knows nothing of storage; the media package implements
 * it.
 */
public interface EmbeddedImages {

	/**
	 * Stores [data], declared as [contentType], as an embedded image: its app-relative address, or
	 * empty when the upload gate refuses it or there is no storage to keep it in.
	 */
	Optional<String> store(byte[] data, String contentType);

	/** Whether [url], an absolute address, points at this server. */
	boolean isOwn(String url);

	/** What a link to an external image says, in the instance's language. */
	String externalImageLabel();

	/** What stands where an image could not be kept, in the instance's language. */
	String imageNotKeptText();
}
