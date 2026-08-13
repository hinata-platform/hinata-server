package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

	/** Message key for "no such image" — the only failure this service reports. */
	private static final String NOT_FOUND = "media";

	/** Bucket "folder" that isolates inline media from attachments/avatars. */
	static final String PREFIX = "media/";

	/** Raster images only; {@code image/svg+xml} is excluded (stored-XSS risk). */
	private static final Set<String> ALLOWED_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/** The random object id (a bare UUID); guards the read path traversal-free. */
	private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");

	private final StorageService storage;
	private final ImagePreviewService previews;

	/**
	 * Uploaded-media metadata returned to the client to build the Markdown URL.
	 * [blurHash] describes the picture in ~30 characters so a caller that keeps it
	 * can paint a placeholder without fetching anything.
	 */
	public record MediaUpload(String url, String fileName, String contentType, long size,
			String blurHash) {
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
				file.getSize(), storePreview(id, file));
	}

	/**
	 * Generates + stores the thumbnail for a freshly uploaded inline image and
	 * returns its BlurHash. A preview failure is never an upload failure — the
	 * image itself is already stored and renders as it always did.
	 */
	private String storePreview(String id, MultipartFile file) {
		try {
			Optional<ImagePreviewService.Preview> preview = previews.create(file.getBytes());
			if (preview.isEmpty()) {
				return null;
			}
			storage.putObject(ImagePreviewService.mediaThumbnailKey(id), preview.get().bytes(),
					preview.get().contentType());
			return preview.get().blurHash();
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Preview for media {} failed: {}", id, ex.toString());
			return null;
		}
	}

	/**
	 * The small preview of an inline image. Images uploaded before previews
	 * existed get theirs generated on first view rather than in a boot-time
	 * migration over the whole bucket; a format the JVM cannot decode (webp)
	 * falls back to the full image, so the caller always gets something
	 * renderable.
	 */
	public StorageService.StoredObject loadThumbnail(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw ApiException.notFound(NOT_FOUND);
		}
		Optional<StorageService.StoredObject> stored =
				storage.getObject(ImagePreviewService.mediaThumbnailKey(id));
		if (stored.isPresent()) {
			return stored.get();
		}
		StorageService.StoredObject original = load(id);
		Optional<ImagePreviewService.Preview> preview = previews.create(original.data());
		if (preview.isEmpty()) {
			return original;
		}
		try {
			storage.putObject(ImagePreviewService.mediaThumbnailKey(id), preview.get().bytes(),
					preview.get().contentType());
		}
		catch (RuntimeException ex) {
			// Caching the result is for the next viewer; this one already has it.
			log.warn("Storing late preview for media {} failed: {}", id, ex.toString());
		}
		return new StorageService.StoredObject(preview.get().bytes(), preview.get().contentType());
	}

	/** Loads stored media bytes by id; only ever returns image content. */
	public StorageService.StoredObject load(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw ApiException.notFound(NOT_FOUND);
		}
		StorageService.StoredObject object = storage.getObject(PREFIX + id)
				.orElseThrow(() -> ApiException.notFound(NOT_FOUND));
		String contentType = object.contentType() == null ? "" : object.contentType().toLowerCase();
		// Defence in depth: never serve a non-image object inline even if one
		// somehow landed under this prefix.
		if (!contentType.startsWith("image/")) {
			throw ApiException.notFound(NOT_FOUND);
		}
		return object;
	}
}
