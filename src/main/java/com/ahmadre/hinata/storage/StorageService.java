package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.extern.slf4j.Slf4j;
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

	public StorageService(HinataProperties properties) {
		this.properties = properties;
		this.backend = createBackend(properties.getStorage());
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
		return upload(file, "");
	}

	/**
	 * Uploads [file] under an optional [keyPrefix] "folder" (e.g. {@code media/})
	 * so different concerns stay isolated in the bucket and can't be read across
	 * endpoints by guessing a bare UUID. The object name is still a random UUID;
	 * user-supplied file names never reach the bucket layout.
	 */
	public String upload(MultipartFile file, String keyPrefix) {
		requireConfigured();
		HinataProperties.Storage storage = properties.getStorage();
		String contentType = file.getContentType();
		if (contentType == null || !storage.getAllowedContentTypes().contains(contentType)) {
			throw ApiException.badRequest("error.storage.fileTypeNotAllowed");
		}
		if (file.getSize() > (long) storage.getMaxUploadMb() * 1024 * 1024) {
			throw ApiException.badRequest("error.storage.fileTooLarge", storage.getMaxUploadMb());
		}
		// The client-declared content type is not trusted on its own: verify the
		// magic bytes for binary types so a file cannot masquerade as e.g. an
		// image (defends against polyglot / content-sniffing attacks, A03/A05).
		verifyMagicBytes(file, contentType);
		String objectKey = keyPrefix + UUID.randomUUID();
		try (var stream = file.getInputStream()) {
			backend.put(objectKey, stream, file.getSize(), contentType);
			return objectKey;
		}
		catch (Exception ex) {
			log.error("Upload failed: {}", ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
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
	private void verifyMagicBytes(MultipartFile file, String contentType) {
		byte[] head = new byte[12];
		int read;
		try (var stream = file.getInputStream()) {
			read = stream.readNBytes(head, 0, head.length);
		}
		catch (Exception ex) {
			throw ApiException.badRequest("error.storage.unreadableUpload");
		}
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
