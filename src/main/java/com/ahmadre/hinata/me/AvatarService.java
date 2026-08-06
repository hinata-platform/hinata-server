package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.media.ImageBounds;
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
	private final FrozenContentService frozen;

	/**
	 * Object key for a user's avatar, e.g. {@code avatars/{userId}.jpg}.
	 *
	 * <p>Static and public because the freeze path has to derive it without this
	 * service: {@code /api/v1/users/*&#47;avatar} is one of the two unauthenticated
	 * content routes, so an account's avatar is the one image in the product that is
	 * served to the open internet, and freezing the account has to reach those bytes.
	 * The key is deterministic, which is what makes deriving it safe.
	 */
	public static String objectKeyFor(String userId) {
		return AVATAR_PREFIX + userId + ".jpg";
	}

	private String objectKey(String userId) {
		return objectKeyFor(userId);
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
		// Both versions, and each tier gets the one it can answer about. The
		// CLASSIFIER judges the re-encoded JPEG: that is what every colleague will
		// actually see next to this person's name in every list, comment and mention,
		// and it is the only version certain to be a decodable raster. The HASH tier
		// judges the bytes that arrived, because an exact-hash programme matches a
		// digest of a known file and a JPEG this server just produced is not that
		// file — passing only the re-encode meant the check ran on every avatar upload
		// and could not have matched. putObject bypasses every check in
		// StorageService, so this is the only gate an avatar ever passes.
		ModerationVerdict verdict = moderation.checkImage(uploaded(file), contentType,
				jpeg, "image/jpeg", file.getOriginalFilename(), ModerationSurface.AVATAR);
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

	/**
	 * The stored avatar bytes for [userId], or empty when none / unset.
	 *
	 * <p>Answers empty for a frozen account, which is the 404 the route already
	 * gives for a user with no avatar. {@code GET /api/v1/users/*&#47;avatar} is
	 * unauthenticated — it is on the {@code permitAll} list because avatars have to
	 * render in an e-mail — so there is no viewer for any per-viewer rule to be
	 * parameterised by, and this is the last check before the bytes leave.
	 *
	 * <p>Belt and braces with the byte chokepoint: freezing a {@code USER} also
	 * freezes {@link #objectKeyFor(String)} as its own {@code OBJECT} row, so
	 * {@code StorageService.getObject} would refuse these bytes anyway. Both exist
	 * because they fail differently — the object row depends on the freeze having
	 * resolved keys correctly, this one only on the account id — and for the one
	 * route in the product that serves user content to the open internet, one guard
	 * is not a margin.
	 */
	public Optional<StorageService.StoredObject> load(String userId) {
		if (!storage.isConfigured() || frozen.isFrozen(FrozenTargetType.USER, userId)) {
			return Optional.empty();
		}
		return storage.getObject(objectKey(userId));
	}

	/**
	 * The bytes exactly as they arrived, for the hash tier.
	 *
	 * <p>Empty rather than throwing when the part cannot be re-read: the compression
	 * below has already read it once and will fail loudly if it cannot, and
	 * {@code judgeImage} falls back to the stored bytes for an empty upload — the
	 * behaviour this surface had before, rather than a refused avatar because a
	 * temp file went missing.
	 */
	private byte[] uploaded(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (java.io.IOException ex) {
			log.warn("Could not re-read the uploaded avatar for hashing: {}", ex.toString());
			return new byte[0];
		}
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor(String userId) {
		return "/api/v1/users/" + userId + "/avatar?v=" + System.currentTimeMillis();
	}

	// --- image pipeline -------------------------------------------------------

	private byte[] compress(MultipartFile file) {
		try {
			// The 12 MB upload bound says nothing about the decoded size: a flat
			// 50000x50000 PNG fits inside it and expands to gigabytes here.
			ImageBounds.requireWithinBudget(file.getBytes(), "error.avatar.imageTooLarge");
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
