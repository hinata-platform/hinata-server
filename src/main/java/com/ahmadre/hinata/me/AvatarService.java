package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.Set;

/**
 * Profile-picture pipeline: decode → center-crop to a square → high-quality
 * (bicubic, progressive halving) downscale to at most {@value #MAX_SIZE}px →
 * re-encode JPEG at quality {@value #JPEG_QUALITY}. Re-encoding from a clean
 * {@link BufferedImage} also strips all EXIF/metadata. The result stays sharp
 * (no upscaling, never below the source) while landing well under a few hundred
 * KB, so avatars are cheap to store and serve.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

	/** Longest edge of the stored avatar; large enough to stay crisp on retina. */
	static final int MAX_SIZE = 512;

	/** Floor so a small source isn't blown up into a blurry/pixelated image. */
	static final int MIN_SIZE = 96;

	static final float JPEG_QUALITY = 0.85f;

	/** Max accepted upload before compression (the photo straight off a phone). */
	private static final long MAX_UPLOAD_BYTES = 12L * 1024 * 1024;

	private static final Set<String> ACCEPTED =
			Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp");

	/** S3 "folder" all user avatars live under (private bucket, server-proxied). */
	private static final String AVATAR_PREFIX = "avatars/";

	private final StorageService storage;
	private final UserRepository users;
	private final ModerationService moderation;
	private final ModerationRecorder moderationRecorder;

	/** Object key for a user's avatar, e.g. {@code avatars/{userId}.jpg}. */
	private String objectKey(String userId) {
		return AVATAR_PREFIX + userId + ".jpg";
	}

	/** Compresses + stores [file] as [user]'s avatar in S3 and returns the URL. */
	public String store(User user, MultipartFile file) {
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

		byte[] jpeg = compress(file);
		// Judged after normalization, not before: the re-encoded JPEG is what every
		// colleague will actually see next to this person's name in every list,
		// comment and mention. It is also the only version that is certainly a
		// decodable raster image, which is what a classifier can answer about.
		// putObject bypasses every check in StorageService, so this is the only
		// gate an avatar ever passes.
		ModerationVerdict verdict = moderation.checkImage(jpeg, "image/jpeg",
				file.getOriginalFilename(), ModerationSurface.AVATAR);
		storage.putObject(objectKey(user.getId()), jpeg, "image/jpeg");
		moderationRecorder.record(verdict, ModerationSurface.AVATAR,
				new ModerationRecorder.Target("user", user.getId(), null, user.getId(),
						user.getDisplayName()));

		String url = urlFor(user.getId());
		user.setAvatarUrl(url);
		users.save(user);
		return url;
	}

	public void remove(User user) {
		storage.delete(objectKey(user.getId()));
		user.setAvatarUrl(null);
		users.save(user);
	}

	/** The stored avatar bytes for [userId], or empty when none / unset. */
	public Optional<StorageService.StoredObject> load(String userId) {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		return storage.getObject(objectKey(userId));
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor(String userId) {
		return "/api/v1/users/" + userId + "/avatar?v=" + System.currentTimeMillis();
	}

	// --- image pipeline -------------------------------------------------------

	private byte[] compress(MultipartFile file) {
		try {
			BufferedImage source = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
			if (source == null) {
				throw ApiException.badRequest("error.avatar.unreadable");
			}
			BufferedImage square = cropSquare(source);
			int target = Math.min(MAX_SIZE, Math.max(MIN_SIZE, square.getWidth()));
			// Never upscale: a small source keeps its size (target capped at source).
			target = Math.min(target, square.getWidth());
			BufferedImage scaled = resize(square, target);
			return encodeJpeg(scaled);
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
	private BufferedImage cropSquare(BufferedImage src) {
		int side = Math.min(src.getWidth(), src.getHeight());
		int x = (src.getWidth() - side) / 2;
		int y = (src.getHeight() - side) / 2;
		return src.getSubimage(x, y, side, side);
	}

	/**
	 * Bicubic downscale with progressive halving (best quality for large ratios):
	 * repeatedly halve toward the target, then a final bicubic pass to the exact
	 * size. Output is flattened onto white so transparent PNGs encode cleanly to
	 * JPEG (which has no alpha channel).
	 */
	private BufferedImage resize(BufferedImage src, int target) {
		BufferedImage current = src;
		int width = src.getWidth();
		int height = src.getHeight();
		while (width / 2 >= target) {
			width /= 2;
			height /= 2;
			current = drawScaled(current, width, height, false);
		}
		return drawScaled(current, target, target, true);
	}

	private BufferedImage drawScaled(BufferedImage src, int w, int h, boolean flattenWhite) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		if (flattenWhite) {
			g.setColor(java.awt.Color.WHITE);
			g.fillRect(0, 0, w, h);
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private byte[] encodeJpeg(BufferedImage image) throws Exception {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
				writer.setOutput(out);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return bytes.toByteArray();
		}
		finally {
			writer.dispose();
		}
	}
}
