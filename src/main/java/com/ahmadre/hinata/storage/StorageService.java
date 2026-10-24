package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Object storage behind a pluggable {@link StorageBackend}: any S3-compatible
 * store (SeaweedFS bundled, MinIO, AWS S3, Google Cloud Storage interop, R2, Spaces, …) or
 * Azure Blob Storage, selected by {@code hivora.storage.provider}. Object keys
 * are random UUIDs – user-supplied file names never reach the file system or
 * bucket layout. Bytes a person sent pass {@link UploadGate} on the way in;
 * backends only move bytes.
 */
@Slf4j
@Service
public class StorageService {

	private final UploadGate gate;
	private final StorageBackend backend;

	@Autowired
	public StorageService(HinataProperties properties, UploadGate gate) {
		this.gate = gate;
		this.backend = createBackend(properties.getStorage());
	}

	/** With a backend made elsewhere; null for one that is not configured. */
	StorageService(UploadGate gate, StorageBackend backend) {
		this.gate = gate;
		this.backend = backend;
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

	/**
	 * What an upload became in the store. [data] holds the stored bytes when they went through
	 * memory, as an embedded image does to lose its metadata; null when they were streamed.
	 */
	public record Stored(String objectKey, String contentType, long size, byte[] data) {
	}

	/**
	 * Stores [candidate] under a fresh key below an optional [keyPrefix] "folder" (e.g.
	 * {@code media/}) so different concerns stay isolated in the bucket and can't be read across
	 * endpoints by guessing a bare UUID. The object name is a random UUID; user-supplied file names
	 * never reach the bucket layout.
	 */
	public Stored upload(UploadCandidate candidate, String keyPrefix) {
		return putUserObject(newObjectKey(keyPrefix), candidate);
	}

	/**
	 * Stores bytes a person sent at [objectKey], once {@link UploadGate} let them through; refused,
	 * nothing is stored. An embedded image loses its metadata on the way (HIN-177) and is held in
	 * memory for that; everything else streams to the store byte for byte, as it was sent.
	 */
	public Stored putUserObject(String objectKey, UploadCandidate candidate) {
		requireConfigured();
		UploadGate.Accepted accepted = gate.check(candidate);
		if (candidate.kind().shownInText()) {
			byte[] clean = gate.withoutMetadata(candidate, accepted);
			putObject(objectKey, clean, accepted.contentType());
			return new Stored(objectKey, accepted.contentType(), clean.length, clean);
		}
		try (InputStream stream = candidate.content().open()) {
			backend.put(objectKey, stream, candidate.size(), accepted.contentType());
		}
		catch (Exception ex) {
			log.error("Upload failed: {}", ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		return new Stored(objectKey, accepted.contentType(), candidate.size(), null);
	}

	/**
	 * A fresh object key under an optional [keyPrefix] "folder", minted exactly
	 * the way {@link #upload(UploadCandidate, String)} mints one: a random UUID,
	 * never a name a client chose.
	 *
	 * <p>Here rather than at the call site because the bucket layout needs one
	 * owner. A caller that writes an object without uploading it — a copy of one,
	 * say — would otherwise carry its own idea of what a key looks like, and a
	 * prefix introduced later would move the uploads and leave that caller
	 * writing into the namespace they used to share.
	 */
	public static String newObjectKey(String keyPrefix) {
		return keyPrefix + UUID.randomUUID();
	}

	/** A fresh key in the bucket's root namespace, the one attachments live in. */
	public static String newObjectKey() {
		return newObjectKey("");
	}

	/** A binary object read back from storage. */
	public record StoredObject(byte[] data, String contentType) {
	}

	/**
	 * Stores bytes the server made itself at an explicit (deterministic) object key —
	 * e.g. {@code avatars/{userId}.jpg}: a preview, or a picture re-encoded from an
	 * input that went through {@link UploadGate}. Unlike {@link #putUserObject} this
	 * trusts the caller, so it checks nothing; which classes may call it is pinned
	 * down by a test ({@code putObjectOnlyForServerBytes}).
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

	/**
	 * Copies an object to a new key inside the store, returning false when the
	 * source is not there or the store refuses. Never throws: the only caller is
	 * duplicating files onto a cloned issue, and a file that will not copy must
	 * cost that issue its attachment, not its existence.
	 *
	 * <p>The copy happens inside the object store — the bytes never travel through
	 * this application, which is what keeps cloning an issue with fifty megabytes
	 * of attachments from pulling fifty megabytes through the JVM twice.
	 */
	public boolean copyObject(String fromKey, String toKey) {
		try {
			// Inside the try, unlike every other method here: a store that is not
			// configured is one more reason a file cannot be copied, and this one
			// answers that with false like it answers all the others.
			requireConfigured();
			backend.copy(fromKey, toKey);
			return true;
		}
		catch (Exception ex) {
			log.warn("Copying object {} to {} failed: {}", fromKey, toKey, ex.getMessage());
			return false;
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
	 * Whether an object is there. For the few callers that must tell a missing object from a store
	 * that refused, like the quarantine: it skips the first and stops on the second.
	 */
	public boolean exists(String objectKey) {
		requireConfigured();
		try {
			return backend.exists(objectKey);
		}
		catch (Exception ex) {
			log.error("Checking object {} failed: {}", objectKey, ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
	}

	/**
	 * Deletes an object and confirms it is gone. {@link #delete} only logs a failure; a removal by
	 * moderation has to know, so this answers false when the store refused or the object is still
	 * there.
	 */
	public boolean deleteVerified(String objectKey) {
		try {
			requireConfigured();
			backend.delete(objectKey);
			return !backend.exists(objectKey);
		}
		catch (Exception ex) {
			log.warn("Deleting object {} failed: {}", objectKey, ex.getMessage());
			return false;
		}
	}

	private void requireConfigured() {
		if (backend == null) {
			throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
					"error.storage.notConfigured");
		}
	}
}
