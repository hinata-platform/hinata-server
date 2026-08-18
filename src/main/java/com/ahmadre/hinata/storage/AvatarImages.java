package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The one avatar pipeline, shared by every round picture the server stores — a
 * person's profile photo, a team's crest, a project's logo: decode → center-crop
 * to a square → high-quality (bicubic, progressive halving) downscale to at most
 * {@value #MAX_SIZE}px → re-encode JPEG at quality {@value #JPEG_QUALITY}.
 * Re-encoding from a clean {@link BufferedImage} also strips all EXIF/metadata,
 * so a photo's GPS coordinates never travel with an avatar. The result stays
 * sharp (no upscaling, never below the source) while landing well under a few
 * hundred KB, so avatars are cheap to store and serve.
 *
 * <p>It lives here rather than in any one domain because all three uploads must
 * behave <em>identically</em> — same limits, same rejection messages, same
 * output. Three copies of this would drift, and the one that drifted would be
 * the one that stopped stripping EXIF.
 *
 * <p>Static like {@link ImageOps} next to it: everything here is a pure function
 * over bytes, so no owner has to inject a bean to re-encode a picture.
 */
@Slf4j
public final class AvatarImages {

	private AvatarImages() {
	}

	/** Longest edge of a stored avatar; large enough to stay crisp on retina. */
	public static final int MAX_SIZE = 512;

	/** Floor so a small source isn't blown up into a blurry/pixelated image. */
	public static final int MIN_SIZE = 96;

	public static final float JPEG_QUALITY = 0.85f;

	/** Max accepted upload before compression (the photo straight off a phone). */
	public static final long MAX_UPLOAD_BYTES = 12L * 1024 * 1024;

	/** What {@link #compress} always produces, whatever went in. */
	public static final String CONTENT_TYPE = "image/jpeg";

	/**
	 * Formats the JDK's ImageIO decodes natively. WebP/SVG are excluded because
	 * there is no built-in reader — an upload the server cannot decode is also
	 * one it cannot strip metadata from.
	 */
	private static final Set<String> ACCEPTED =
			Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp");

	/** Query parameter carrying the avatar's BlurHash. */
	private static final String BLUR_PARAM = "bh=";

	/**
	 * Validates [file] and returns the square, downscaled, metadata-free JPEG to
	 * store. The declared content type is only a first gate: the payload still has
	 * to decode as a real raster image, so a script renamed to {@code .png} is
	 * rejected here rather than served back to a browser later.
	 */
	public static byte[] compress(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("error.avatar.empty");
		}
		if (file.getSize() > MAX_UPLOAD_BYTES) {
			throw ApiException.badRequest("error.avatar.tooLarge");
		}
		String contentType = file.getContentType();
		if (contentType == null || !ACCEPTED.contains(contentType.toLowerCase())) {
			throw ApiException.badRequest("error.avatar.unsupportedType");
		}
		try {
			// Bounded decode: a small file can declare enormous dimensions, and an
			// avatar is not worth the heap that would take (see ImageOps.read).
			BufferedImage source = ImageOps.read(file.getBytes());
			if (source == null) {
				throw ApiException.badRequest("error.avatar.unreadable");
			}
			BufferedImage square = cropSquare(source);
			int target = Math.min(MAX_SIZE, Math.max(MIN_SIZE, square.getWidth()));
			// Never upscale: a small source keeps its size (target capped at source).
			target = Math.min(target, square.getWidth());
			// keepAlpha=false: JPEG has no alpha channel, so a transparent source is
			// flattened onto white rather than turned black.
			BufferedImage scaled = ImageOps.scaleTo(square, target, target, false);
			return ImageOps.encodeJpeg(scaled, JPEG_QUALITY);
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Avatar compression failed: {}", ex.getMessage());
			throw ApiException.badRequest("error.avatar.unreadable");
		}
	}

	/** Center-crops to the largest centered square. */
	static BufferedImage cropSquare(BufferedImage src) {
		int side = Math.min(src.getWidth(), src.getHeight());
		int x = (src.getWidth() - side) / 2;
		int y = (src.getHeight() - side) / 2;
		return src.getSubimage(x, y, side, side);
	}

	/** BlurHash of already-compressed avatar bytes, or null when unreadable. */
	public static String blurHashOf(ImagePreviewService previews, byte[] jpeg) {
		return previews.create(jpeg).map(ImagePreviewService.Preview::blurHash).orElse(null);
	}

	/** Whether an avatar URL already carries its BlurHash. */
	public static boolean hasBlurHash(String url) {
		return url != null && url.contains(BLUR_PARAM);
	}

	/**
	 * Appends the BlurHash to an avatar URL.
	 *
	 * <p>In the URL rather than in a response field, because an avatar's address
	 * already travels in every DTO that mentions its owner — the directory, a
	 * board card, a search hit, an audit row. A separate field would have to be
	 * added to each of them and threaded through every widget that draws a
	 * circle; the address is the one thing they all already carry.
	 *
	 * <p>The hash is percent-encoded: base-83 includes {@code # $ % + ? &}, all
	 * of which would otherwise cut the URL short or invent a parameter.
	 */
	public static String withBlurHash(String url, String blurHash) {
		if (blurHash == null || blurHash.isBlank()) {
			return url;
		}
		return url + (url.contains("?") ? "&" : "?") + BLUR_PARAM
				+ URLEncoder.encode(blurHash, StandardCharsets.UTF_8);
	}
}
