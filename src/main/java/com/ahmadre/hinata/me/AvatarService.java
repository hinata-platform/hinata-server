package com.ahmadre.hinata.me;

import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.storage.AvatarImages;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.UploadGate;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * A user's profile picture: where it is stored and which URL points at it. The
 * picture itself is validated, cropped, downscaled and stripped of metadata by
 * {@link AvatarImages} — the same pipeline team and project avatars run through,
 * so all three obey the same limits and produce the same kind of file.
 */
@Service
@RequiredArgsConstructor
public class AvatarService {

	/** S3 "folder" all user avatars live under (private bucket, server-proxied). */
	private static final String AVATAR_PREFIX = "avatars/";

	private final StorageService storage;
	private final UserRepository users;
	private final ImagePreviewService previews;
	private final UploadGate gate;

	/** Object key for a user's avatar, e.g. {@code avatars/{userId}.jpg}. */
	public static String objectKey(String userId) {
		return AVATAR_PREFIX + userId + ".jpg";
	}

	/** Compresses + stores [file] as [user]'s avatar in S3 and returns the URL. */
	public String store(User user, MultipartFile file) {
		byte[] jpeg = AvatarImages.compress(gate, file);
		// Deterministic key in the avatars/ folder: re-uploads overwrite cleanly,
		// no orphaned objects. The bucket stays private; bytes are served back
		// through the AvatarController proxy, so S3 credentials never leave the
		// server and no public bucket policy is required.
		storage.putObject(objectKey(user.getId()), jpeg, AvatarImages.CONTENT_TYPE);

		String url = AvatarImages.withBlurHash(urlFor(user.getId()),
				AvatarImages.blurHashOf(previews, jpeg));
		user.setAvatarUrl(url);
		// A picture moderation removed, replaced by a new one, is the new one (HIN-169).
		ContentMark mark = user.getModeration() == null ? null : user.getModeration().get("avatar");
		if (mark != null && mark.isRemoved()) {
			java.util.Map<String, ContentMark> marks = new java.util.LinkedHashMap<>(user.getModeration());
			marks.put("avatar", mark.rewritten());
			user.setModeration(marks);
		}
		users.save(user);
		return url;
	}

	public void remove(User user) {
		storage.delete(objectKey(user.getId()));
		user.setAvatarUrl(null);
		users.save(user);
	}

	/** A person's picture as the public route serves it, with the version its tag names. */
	public record Avatar(StorageService.StoredObject object, String etag) {
	}

	/**
	 * The stored avatar of [userId], or empty when there is no such account, it is deactivated,
	 * or it shows no picture (HIN-169): the route answers without a token, and a removed or
	 * abandoned picture must not stay reachable by an old link.
	 *
	 * <p>Also fills in a missing BlurHash on the way out: pictures uploaded before placeholders
	 * existed have no hash in their URL, and this is the one moment the server holds their bytes
	 * anyway. The refreshed URL reaches clients with the next response that mentions this user.
	 */
	public Optional<Avatar> load(String userId) {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		User user = users.findById(userId).orElse(null);
		if (user == null || !user.isActive() || user.getAvatarUrl() == null) {
			return Optional.empty();
		}
		Optional<StorageService.StoredObject> object = storage.getObject(objectKey(userId));
		object.ifPresent(stored -> backfillBlurHash(user, stored.data()));
		// The URL carries the upload's time, so a new picture is a new tag.
		String etag = "\"" + ContentSnapshot.hashOf(user.getAvatarUrl()).substring(0, 16) + "\"";
		return object.map(stored -> new Avatar(stored, etag));
	}

	private void backfillBlurHash(User owner, byte[] jpeg) {
		Optional.of(owner)
				.filter(user -> user.getAvatarUrl() != null
						&& !AvatarImages.hasBlurHash(user.getAvatarUrl()))
				.ifPresent(user -> {
					String hash = AvatarImages.blurHashOf(previews, jpeg);
					if (hash == null) {
						return;
					}
					// The existing URL is kept verbatim apart from the new parameter:
					// re-stamping its cache-buster would make every client refetch a
					// picture that has not changed.
					user.setAvatarUrl(AvatarImages.withBlurHash(user.getAvatarUrl(), hash));
					users.save(user);
				});
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor(String userId) {
		return "/api/v1/users/" + userId + "/avatar?v=" + System.currentTimeMillis();
	}
}
