package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.media.ImageBounds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Object storage behind a pluggable {@link StorageBackend}: any S3-compatible
 * store (MinIO in dev, AWS S3, Google Cloud Storage interop, R2, Spaces, …) or
 * Azure Blob Storage, selected by {@code hivora.storage.provider}. Object keys
 * are random UUIDs – user-supplied file names never reach the file system or
 * bucket layout. This service owns all validation and error mapping; backends
 * only move bytes.
 */
@Slf4j
@Service
public class StorageService {

	private final HinataProperties properties;
	private final StorageBackend backend;

	/**
	 * The image gate, or {@code null} on an instance built outside the container.
	 *
	 * @see #StorageService(HinataProperties)
	 */
	private final ModerationService moderation;

	private final ModerationRecorder moderationRecorder;

	@Autowired
	public StorageService(HinataProperties properties, ModerationService moderation,
			ModerationRecorder moderationRecorder) {
		this.properties = properties;
		this.moderation = moderation;
		this.moderationRecorder = moderationRecorder;
		this.backend = createBackend(properties.getStorage());
	}

	/**
	 * Backend selection without the image gate, for the unit tests that only ask
	 * which backend a configuration selects and never put a byte anywhere.
	 *
	 * <p>The constructor above carries {@code @Autowired} because this one exists:
	 * Spring resolves two unannotated constructors by falling back to the shortest
	 * one, which would leave the product uploading unclassified images.
	 */
	public StorageService(HinataProperties properties) {
		this(properties, null, null);
	}

	private static StorageBackend createBackend(HinataProperties.Storage storage) {
		return switch (storage.getProvider()) {
			case "azure" -> storage.getAzureConnectionString().isBlank() ? null
					: new AzureBlobStorageBackend(storage);
			case "s3" -> storage.getAccessKey().isBlank() ? null
					: new S3StorageBackend(storage);
			default -> throw new IllegalStateException(
					"Unknown hivora.storage.provider '" + storage.getProvider() + "' (expected s3 or azure)");
		};
	}

	public boolean isConfigured() {
		return backend != null;
	}

	/** Maximum accepted upload size in bytes (mirrors the multipart limit). */
	public long maxUploadBytes() {
		return (long) properties.getStorage().getMaxUploadMb() * 1024 * 1024;
	}

	public String upload(MultipartFile file) {
		return upload(file, "", ModerationSurface.ATTACHMENT);
	}

	/** As {@link #upload(MultipartFile, String, ModerationSurface)}, as an attachment. */
	public String upload(MultipartFile file, String keyPrefix) {
		return upload(file, keyPrefix, ModerationSurface.ATTACHMENT);
	}

	/**
	 * Uploads [file] under an optional [keyPrefix] "folder" (e.g. {@code media/})
	 * so different concerns stay isolated in the bucket and can't be read across
	 * endpoints by guessing a bare UUID. The object name is still a random UUID;
	 * user-supplied file names never reach the bucket layout.
	 *
	 * <p>[surface] is what the bytes are being used <em>for</em> — an attachment
	 * nobody sees until they open it is not the same risk as an image that renders
	 * inline in every reader's feed — so it comes from the caller rather than
	 * being guessed from the key prefix.
	 */
	public String upload(MultipartFile file, String keyPrefix, ModerationSurface surface) {
		requireConfigured();
		String contentType = file.getContentType();
		// The declared size decides before a byte is copied. Classifying an image
		// means holding it in memory, so an oversized upload has to be refused off
		// the part's own metadata rather than after materializing it — validate()
		// re-checks against the real length, which is the number that binds.
		requireWithinSizeLimit(file.getSize());
		byte[] data = read(file);
		String objectKey = keyPrefix + UUID.randomUUID();
		String fileName = file.getOriginalFilename();
		ModerationVerdict verdict = validate(data, contentType, fileName, surface);
		try (var stream = new ByteArrayInputStream(data)) {
			backend.put(objectKey, stream, data.length, contentType);
			recordVerdict(verdict, surface, objectKey, fileName);
			return objectKey;
		}
		catch (Exception ex) {
			log.error("Upload failed: {}", ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
	}

	/**
	 * Stores already-read bytes through the <em>same</em> checks as
	 * {@link #upload}: the content-type allowlist, the size bound, the magic-byte
	 * verification and the image gate.
	 *
	 * <p>Exists because {@link #putObject} deliberately trusts its caller, and one
	 * caller must never be trusted: an e-mail attachment, whose declared content
	 * type is a MIME header written by whoever sent the mail. Bytes that did not
	 * arrive as a multipart request are still bytes a colleague will open.
	 */
	public String putChecked(String keyPrefix, byte[] data, String contentType, String fileName,
			ModerationSurface surface) {
		requireConfigured();
		String objectKey = keyPrefix + UUID.randomUUID();
		ModerationVerdict verdict = validate(data, contentType, fileName, surface);
		try (var stream = new ByteArrayInputStream(data)) {
			backend.put(objectKey, stream, data.length, contentType);
			recordVerdict(verdict, surface, objectKey, fileName);
			return objectKey;
		}
		catch (Exception ex) {
			log.error("Upload of {} failed: {}", fileName, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
	}

	/**
	 * Everything that has to be true of a byte array before it is stored, in the
	 * order that costs least: an allow-listed type, a bounded size, a signature
	 * matching the declared type, and only then the classifier.
	 *
	 * @return the classifier's verdict, or {@code null} when nothing judged these
	 *         bytes — a non-image, or an instance built without the gate
	 */
	private ModerationVerdict validate(byte[] data, String contentType, String fileName,
			ModerationSurface surface) {
		if (contentType == null
				|| !properties.getStorage().getAllowedContentTypes().contains(contentType)) {
			throw ApiException.badRequest("error.storage.fileTypeNotAllowed");
		}
		requireWithinSizeLimit(data.length);
		// The client-declared content type is not trusted on its own: verify the
		// magic bytes for binary types so a file cannot masquerade as e.g. an
		// image (defends against polyglot / content-sniffing attacks, A03/A05).
		verifyMagicBytes(data, contentType);
		if (!contentType.startsWith("image/")) {
			return null;
		}
		// Nothing here decodes, so this is not defending its own heap — it is
		// defending whatever opens the bytes next. The classifier below is one such
		// reader and lives out of process; refusing the pixel bomb at the door means
		// the sidecar never has to survive it, and neither does a future thumbnailer.
		// Silent for formats no reader recognises (WebP has no decoder in this JDK):
		// bytes nobody here can open are bytes nobody here can be flooded by.
		ImageBounds.requireWithinBudget(data, "error.storage.imageTooLarge");
		// Only after the signature check: a classifier fed bytes that are not the
		// format they claim to be is answering a question about a file nobody will
		// ever see.
		if (moderation == null) {
			return null;
		}
		return moderation.checkImage(data, contentType, fileName, surface);
	}

	/**
	 * Files a surviving suspicion against the object that was just written.
	 *
	 * <p>Recorded here rather than by the caller because the object key is the
	 * durable reference a moderator needs and it does not exist until the put
	 * succeeds — and because every caller would otherwise repeat this, and one of
	 * them would forget. Only ever a reference: the bytes stay in the bucket, the
	 * queue row holds the verdict and the file name.
	 */
	private void recordVerdict(ModerationVerdict verdict, ModerationSurface surface,
			String objectKey, String fileName) {
		if (verdict == null || moderationRecorder == null) {
			return;
		}
		String type = surface == ModerationSurface.INLINE_IMAGE ? "media" : "attachment";
		moderationRecorder.record(verdict, surface,
				new ModerationRecorder.Target(type, objectKey, null, null, fileName));
	}

	private void requireWithinSizeLimit(long bytes) {
		int maxMb = properties.getStorage().getMaxUploadMb();
		if (bytes > (long) maxMb * 1024 * 1024) {
			throw ApiException.badRequest("error.storage.fileTooLarge", maxMb);
		}
	}

	private byte[] read(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (Exception ex) {
			throw ApiException.badRequest("error.storage.unreadableUpload");
		}
	}

	/** A binary object read back from storage. */
	public record StoredObject(byte[] data, String contentType) {
	}

	/**
	 * Stores already-prepared bytes at an explicit (deterministic) object key —
	 * e.g. {@code avatars/{userId}.jpg}. Unlike {@link #upload(MultipartFile)}
	 * this trusts the caller (used for server-generated, already-validated and
	 * compressed content), so it does no content-type allow-listing.
	 */
	public void putObject(String objectKey, byte[] data, String contentType) {
		requireConfigured();
		try {
			backend.put(objectKey, new ByteArrayInputStream(data), data.length, contentType);
		}
		catch (Exception ex) {
			log.error("Put object {} failed: {}", objectKey, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
					"error.storage.unavailable");
		}
	}

	/** Reads an object's bytes + content type, or empty when it doesn't exist. */
	public Optional<StoredObject> getObject(String objectKey) {
		requireConfigured();
		try {
			return backend.get(objectKey);
		}
		catch (Exception ex) {
			log.error("Reading object {} failed: {}", objectKey, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
					"error.storage.unavailable");
		}
	}

	public String presignedDownloadUrl(String objectKey, String fileName) {
		requireConfigured();
		try {
			return backend.presignedDownloadUrl(objectKey, fileName.replaceAll("[\"\\\\]", "_"));
		}
		catch (Exception ex) {
			log.error("Presigning object {} failed: {}", objectKey, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
	}

	/** A stored object's key and when it was last written. */
	public record ObjectInfo(String key, Instant lastModified) {
	}

	/**
	 * Lists every object under [keyPrefix] with its last-modified time. Used by
	 * the inline-media orphan sweep to find candidates for deletion; the object
	 * store itself is the source of truth for the upload time, so no separate
	 * metadata collection is needed.
	 */
	public List<ObjectInfo> list(String keyPrefix) {
		requireConfigured();
		try {
			return backend.list(keyPrefix);
		}
		catch (Exception ex) {
			log.error("Listing objects under {} failed: {}", keyPrefix, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
	}

	public void delete(String objectKey) {
		requireConfigured();
		try {
			backend.delete(objectKey);
		}
		catch (Exception ex) {
			log.warn("Deleting object {} failed: {}", objectKey, ex.getMessage());
		}
	}

	/**
	 * Verifies the leading bytes of binary uploads against the declared content
	 * type. Text-like types (text/*, application/json) have no fixed signature
	 * and are stored as-is; all downloads are served with
	 * {@code Content-Disposition: attachment}, so they are never rendered inline.
	 */
	private void verifyMagicBytes(byte[] data, String contentType) {
		byte[] head = new byte[12];
		int read = Math.min(head.length, data.length);
		System.arraycopy(data, 0, head, 0, read);
		boolean ok = switch (contentType) {
			case "image/png" -> startsWith(head, read, 0x89, 0x50, 0x4E, 0x47);
			case "image/jpeg" -> startsWith(head, read, 0xFF, 0xD8, 0xFF);
			case "image/gif" -> startsWith(head, read, 0x47, 0x49, 0x46, 0x38);
			case "image/webp" -> read >= 12
					&& startsWith(head, read, 0x52, 0x49, 0x46, 0x46)
					&& head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50;
			case "application/pdf" -> startsWith(head, read, 0x25, 0x50, 0x44, 0x46);
			// ZIP-based: application/zip and the OOXML office documents (docx/xlsx).
			case "application/zip",
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
					startsWith(head, read, 0x50, 0x4B);
			// No reliable signature; stored as-is (downloaded, never rendered).
			default -> true;
		};
		if (!ok) {
			throw ApiException.badRequest("error.storage.contentMismatch");
		}
	}

	private static boolean startsWith(byte[] data, int len, int... prefix) {
		if (len < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if ((data[i] & 0xFF) != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private void requireConfigured() {
		if (backend == null) {
			throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
					"error.storage.notConfigured");
		}
	}
}
