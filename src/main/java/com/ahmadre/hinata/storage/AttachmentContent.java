package com.ahmadre.hinata.storage;

/**
 * The bounded, model-safe rendering of one attachment — what an AI client is
 * allowed to receive instead of the stored bytes.
 *
 * <p>Deliberately a closed set: a picture that was decoded and re-encoded at a
 * bounded size, text that was extracted and length-capped, or nothing at all
 * with a reason. There is no "here are the original bytes" case, because that is
 * exactly what must never end up in a model's context window — neither for size
 * nor for what a raw file carries with it (EXIF/GPS survives a copy, it does not
 * survive a re-encode).
 */
public sealed interface AttachmentContent {

	/**
	 * A picture, decoded and re-encoded at [width]×[height] — never the stored
	 * object. [sourceWidth]/[sourceHeight] are the original dimensions so a
	 * caller can tell how much detail it is looking at.
	 */
	record Image(byte[] bytes, String contentType, int width, int height,
			int sourceWidth, int sourceHeight) implements AttachmentContent {
	}

	/**
	 * Extracted text (a text file's content, or a PDF's pages). [truncated] says
	 * the extract stops short of the whole file, so the caller can decide to open
	 * it properly rather than reason about a fragment believing it is complete.
	 */
	record Text(String text, boolean truncated) implements AttachmentContent {
	}

	/** Nothing renderable, with the reason a caller can act on. */
	record Unavailable(Reason reason) implements AttachmentContent {
	}

	/** Why an attachment has no renderable content. */
	enum Reason {
		/** Over the configured raw-size budget — refused whole, never half-sent. */
		TOO_LARGE,
		/** A type with no sensible representation in a text/image conversation. */
		TYPE_NOT_RENDERABLE,
		/** The right type, but the payload could not be decoded (webp, corrupt, encrypted). */
		UNREADABLE,
		/** The stored object is gone. */
		MISSING
	}
}
