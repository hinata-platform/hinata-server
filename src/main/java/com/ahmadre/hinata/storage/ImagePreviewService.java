package com.ahmadre.hinata.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Turns an uploaded picture into the two things a client needs before it can
 * show anything: a small <b>thumbnail</b> (a grid of photos costs one downscaled
 * image each instead of the originals) and a <b>BlurHash</b> — a ~30 character
 * string that travels inside the JSON listing the picture, so the placeholder is
 * a blurred version of the actual image in the very first frame rather than an
 * empty box.
 *
 * <p>Everything here degrades quietly: a payload the JDK cannot decode (webp has
 * no reader, and a file may simply be broken) yields no preview, never a failed
 * upload. The client then falls back to the full image, which is exactly what it
 * used to do for every picture.
 */
@Slf4j
@Service
public class ImagePreviewService {

	/** Longest edge of a stored thumbnail — sharp in a grid tile and in a list. */
	public static final int THUMBNAIL_MAX_EDGE = 480;

	private static final float JPEG_QUALITY = 0.78f;

	/**
	 * Bucket "folder" holding every derived thumbnail. Deliberately outside the
	 * {@code media/} prefix: the inline-media orphan sweep reaps everything under
	 * that prefix whose id no content references, and a thumbnail is referenced
	 * by nothing — it would be deleted a grace window after it was written.
	 */
	public static final String PREFIX = "thumbs/";

	/**
	 * Where an issue attachment's thumbnail lives. Derived from the attachment id
	 * so nothing extra has to be stored to find (or delete) it again.
	 */
	public static String attachmentThumbnailKey(String attachmentId) {
		return PREFIX + "att/" + attachmentId;
	}

	/** Where an inline-media image's thumbnail lives (same reasoning). */
	public static String mediaThumbnailKey(String mediaId) {
		return PREFIX + "media/" + mediaId;
	}

	/**
	 * A generated preview: the thumbnail's bytes and content type plus the
	 * BlurHash of the same picture.
	 */
	public record Preview(byte[] bytes, String contentType, String blurHash) {
	}

	/**
	 * Builds the preview for [source], or empty when the bytes are not a raster
	 * image this JVM can decode.
	 */
	public Optional<Preview> create(byte[] source) {
		BufferedImage image = ImageOps.read(source);
		if (image == null) {
			return Optional.empty();
		}
		try {
			boolean alpha = ImageOps.hasAlpha(image);
			BufferedImage thumbnail = ImageOps.scaleWithin(image, THUMBNAIL_MAX_EDGE, alpha);
			// PNG only where transparency has to survive: a JPEG would flatten it
			// to white and flash against the app's dark surfaces when the real
			// image arrives. Everything else is a JPEG, which is far smaller.
			byte[] bytes = alpha
					? ImageOps.encodePng(thumbnail)
					: ImageOps.encodeJpeg(thumbnail, JPEG_QUALITY);
			return Optional.of(new Preview(bytes, alpha ? "image/png" : "image/jpeg", blurHash(image)));
		}
		catch (Exception ex) {
			log.warn("Preview generation failed: {}", ex.toString());
			return Optional.empty();
		}
	}

	/**
	 * BlurHash of an already tiny copy of the image: the encoder is quadratic in
	 * the pixel count and the format keeps only the lowest frequencies, so more
	 * pixels cost time and change nothing. Alpha is flattened first — a
	 * transparent pixel's colour channels are undefined, and reading them raw
	 * would tint the placeholder black.
	 */
	private String blurHash(BufferedImage image) {
		BufferedImage small = ImageOps.scaleWithin(image, BlurHash.MAX_SOURCE_EDGE, false);
		if (ImageOps.hasAlpha(small)) {
			small = ImageOps.scaleTo(small, small.getWidth(), small.getHeight(), false);
		}
		return BlurHash.encode(small);
	}
}
