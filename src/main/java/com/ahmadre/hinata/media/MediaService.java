package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Inline Markdown media (images embedded while authoring an issue description,
 * comment or knowledge-base article). Unlike issue attachments, media is not
 * bound to a single entity — it is referenced by URL from arbitrary Markdown —
 * so it lives under its own {@code media/} prefix and is readable by any
 * authenticated user (the same audience that can open the content it is embedded
 * in). Bytes are always proxied back through {@link MediaController}; the object
 * store stays private and no storage URL ever reaches a client.
 */
@Service
@RequiredArgsConstructor
public class MediaService {

	/** Bucket "folder" that isolates inline media from attachments/avatars. */
	static final String PREFIX = "media/";

	/** Raster images only; {@code image/svg+xml} is excluded (stored-XSS risk). */
	private static final Set<String> ALLOWED_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/** The random object id (a bare UUID); guards the read path traversal-free. */
	private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");

	private final StorageService storage;

	/** Uploaded-media metadata returned to the client to build the Markdown URL. */
	public record MediaUpload(String url, String fileName, String contentType, long size) {
	}

	/** Validates + stores an inline image, returning its app-relative URL. */
	public MediaUpload store(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("error.media.empty");
		}
		String contentType = file.getContentType();
		if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
			throw ApiException.badRequest("error.media.notAnImage");
		}
		// StorageService re-checks size + magic bytes against the declared type.
		String objectKey = storage.upload(file, PREFIX);
		String id = objectKey.substring(PREFIX.length());
		return new MediaUpload("/api/v1/media/" + id, file.getOriginalFilename(), contentType,
				file.getSize());
	}

	/** Loads stored media bytes by id; only ever returns image content. */
	public StorageService.StoredObject load(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw ApiException.notFound("media");
		}
		StorageService.StoredObject object = storage.getObject(PREFIX + id)
				.orElseThrow(() -> ApiException.notFound("media"));
		String contentType = object.contentType() == null ? "" : object.contentType().toLowerCase();
		// Defence in depth: never serve a non-image object inline even if one
		// somehow landed under this prefix.
		if (!contentType.startsWith("image/")) {
			throw ApiException.notFound("media");
		}
		return object;
	}
}
