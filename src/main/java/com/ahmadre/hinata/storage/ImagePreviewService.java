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
	 * Whether a preview is worth attempting for this content type: pictures, and
	 * PDFs (whose first page is rendered). A Word file or an archive has no page
	 * to draw, and reading a 20 MB ZIP back out of storage to discover that is
	 * exactly the work this check avoids.
	 *
	 * <p>It is also the answer to whether a thumbnail can exist at all — every
	 * path that writes one asks this first — which is what lets a caller skip the
	 * key below for an attachment that can never have anything under it, instead
	 * of asking the store and being told the same thing by a 404.
	 */
	public static boolean isPreviewable(String contentType) {
		if (contentType == null) {
			return false;
		}
		String type = contentType.toLowerCase();
		return type.startsWith("image/") || type.startsWith("application/pdf");
	}

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
	 * Builds the preview for [source]: a raster image, or the first page of a
	 * PDF. Empty when the bytes are neither — a Word file or a ZIP has no page
	 * to draw, and the client shows its file-type glyph as it always did.
	 *
	 * <p>The kind is decided by the payload's own magic bytes, not by a
	 * client-declared content type, so a mislabelled upload is previewed as what
	 * it actually is (or not at all).
	 */
	public Optional<Preview> create(byte[] source) {
		BufferedImage image = FileSignature.isPdf(source) ? PdfPageRenderer.firstPage(source)
				: ImageOps.read(source);
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
