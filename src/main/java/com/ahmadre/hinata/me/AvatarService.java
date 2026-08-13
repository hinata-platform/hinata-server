package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.ImageOps;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
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
		// Deterministic key in the avatars/ folder: re-uploads overwrite cleanly,
		// no orphaned objects. The bucket stays private; bytes are served back
		// through the AvatarController proxy, so S3 credentials never leave the
		// server and no public bucket policy is required.
		storage.putObject(objectKey(user.getId()), jpeg, "image/jpeg");

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
	private BufferedImage cropSquare(BufferedImage src) {
		int side = Math.min(src.getWidth(), src.getHeight());
		int x = (src.getWidth() - side) / 2;
		int y = (src.getHeight() - side) / 2;
		return src.getSubimage(x, y, side, side);
	}
}
