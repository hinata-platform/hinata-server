package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.storage.StorageService.ObjectInfo;
import com.ahmadre.hinata.storage.StorageService.StoredObject;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Provider-agnostic object-storage operations. {@link StorageService} owns all
 * validation (content types, size limits, magic bytes) and error mapping;
 * implementations only translate these primitives to a concrete store
 * (S3-compatible via {@link S3StorageBackend}, Azure Blob Storage via
 * {@link AzureBlobStorageBackend}).
 *
 * <p>Implementations throw provider exceptions as-is — except {@link #get},
 * which maps "object does not exist" to {@link Optional#empty()} so the
 * service never has to know provider-specific not-found codes.
 */
public interface StorageBackend {

	/**
	 * Writes [length] bytes from [stream] under [objectKey], creating the
	 * bucket/container on first use.
	 */
	void put(String objectKey, InputStream stream, long length, String contentType) throws Exception;

	/** Reads an object, or empty when it doesn't exist. */
	Optional<StoredObject> get(String objectKey) throws Exception;

	/** Deletes an object; deleting a missing object is not an error. */
	void delete(String objectKey) throws Exception;

	/** Lists every object under [keyPrefix], recursively. */
	List<ObjectInfo> list(String keyPrefix) throws Exception;

	/**
	 * A time-limited public download URL that forces
	 * {@code Content-Disposition: attachment} with [fileName].
	 */
	String presignedDownloadUrl(String objectKey, String fileName) throws Exception;
}
