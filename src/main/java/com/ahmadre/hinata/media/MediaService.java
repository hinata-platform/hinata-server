package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.UploadCandidate;
import com.ahmadre.hinata.storage.UploadKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
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

	/**
	 * Stores an inline image, returning its app-relative URL. Raster images only, as
	 * {@link UploadKind#MEDIA} allows them; {@code image/svg+xml} is not among them
	 * (stored-XSS risk). The gate checks it and takes its metadata out before it is
	 * stored, so a photo's GPS position never reaches the people reading the page.
	 */
	public MediaUpload store(MultipartFile file) {
		return store(UploadCandidate.of(UploadKind.MEDIA, file));
	}

	/**
	 * Stores an image that arrived inside a text as {@code data:} bytes, as an upload is
	 * stored: through the gate, without its metadata (HIN-177). Its thumbnail is made when
	 * somebody first looks ({@link #loadThumbnail}): one save brings up to twenty of them,
	 * and none of it would wait for twenty decodes.
	 */
	public String storePasted(byte[] data, String declaredType) {
		StorageService.Stored stored = storage.upload(UploadCandidate.of(UploadKind.PASTED_IMAGE, data, declaredType,
				null), PREFIX);
		return urlOf(stored);
	}

	private MediaUpload store(UploadCandidate candidate) {
		StorageService.Stored stored = storage.upload(candidate, PREFIX);
		String id = stored.objectKey().substring(PREFIX.length());
		return new MediaUpload(urlOf(stored), candidate.fileName(), stored.contentType(),
				stored.size(), storePreview(id, stored.data()));
	}

	private static String urlOf(StorageService.Stored stored) {
		return "/api/v1/media/" + stored.objectKey().substring(PREFIX.length());
	}

	/**
	 * Generates + stores the thumbnail for a freshly stored inline image from the
	 * bytes as stored and returns its BlurHash. A preview failure is never an
	 * upload failure — the image itself is already stored and renders as it
	 * always did.
	 */
	private String storePreview(String id, byte[] stored) {
		try {
			Optional<ImagePreviewService.Preview> preview = previews.create(stored);
			if (preview.isEmpty()) {
				return null;
			}
			storage.putObject(ImagePreviewService.mediaThumbnailKey(id), preview.get().bytes(),
					preview.get().contentType());
			return preview.get().blurHash();
		}
		catch (RuntimeException ex) {
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
