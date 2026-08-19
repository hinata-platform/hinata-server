package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.StorageService.ObjectInfo;
import com.ahmadre.hinata.storage.StorageService.StoredObject;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Azure Blob Storage backend (native API — Azure does not speak the S3
 * protocol). Authenticated with the storage account's connection string; the
 * configured bucket name is used as the blob container. Presigned downloads
 * are service SAS URLs, which requires an account-key-based connection string
 * (the default from the portal's "Access keys" blade).
 */
class AzureBlobStorageBackend implements StorageBackend {

	/**
	 * How long a copy's source signature stays valid. The blob service reads the
	 * source while {@code copy} is blocked on it, and the URL is never handed to
	 * anyone, so this only has to outlast one server-side copy.
	 */
	private static final int COPY_SOURCE_SAS_MINUTES = 10;

	private final BlobContainerClient container;

	AzureBlobStorageBackend(HinataProperties.Storage storage) {
		BlobServiceClient service = new BlobServiceClientBuilder()
				.connectionString(storage.getAzureConnectionString())
				.buildClient();
		this.container = service.getBlobContainerClient(storage.getBucket());
	}

	@Override
	public void put(String objectKey, InputStream stream, long length, String contentType) throws Exception {
		container.createIfNotExists();
		container.getBlobClient(objectKey).uploadWithResponse(
				new BlobParallelUploadOptions(BinaryData.fromStream(stream, length))
						.setHeaders(new BlobHttpHeaders().setContentType(contentType)),
				null, null);
	}

	@Override
	public void copy(String fromKey, String toKey) throws Exception {
		// Azure's copy is asynchronous by protocol; copyFromUrl blocks until it has
		// finished, which is what every caller here wants — a clone that returns
		// before its files exist would show empty tiles.
		//
		// No createIfNotExists() either, unlike put: source and destination share
		// this container, so a container that would have to be created holds no
		// source to copy — and it is a request to the service like any other, which
		// a clone would make per object and per thumbnail it duplicates.
		container.getBlobClient(toKey).copyFromUrl(signedSourceUrl(fromKey));
	}

	/**
	 * The URL {@code Copy Blob From URL} is given for the source, signed with a
	 * short-lived read SAS.
	 *
	 * <p>The signature is not optional. The account key on this client authorizes
	 * the <em>destination</em> write; the source is fetched by the blob service
	 * over HTTP as an ordinary reader, and this container is private (that is the
	 * whole reason downloads are handed out as SAS URLs), so an unsigned source URL
	 * comes back 404 and the copy fails. Shared-key credentials cannot be presented
	 * for the source any other way — the {@code x-ms-copy-source-authorization}
	 * header carries bearer tokens only — so the URL carries its own.
	 *
	 * <p>Minutes, not hours: the service reads the source while the call is
	 * blocked, and the URL is never handed to anyone. It also never leaves this
	 * account — the blob client builds it from the configured endpoint and
	 * container, and [fromKey] is a key this application generated, so there is
	 * nothing here for a caller to point somewhere else.
	 */
	String signedSourceUrl(String fromKey) {
		BlobClient source = container.getBlobClient(fromKey);
		String sas = source.generateSas(new BlobServiceSasSignatureValues(
				OffsetDateTime.now().plusMinutes(COPY_SOURCE_SAS_MINUTES),
				new BlobSasPermission().setReadPermission(true)));
		return source.getBlobUrl() + "?" + sas;
	}

	@Override
	public Optional<StoredObject> get(String objectKey) throws Exception {
		try {
			BlobClient blob = container.getBlobClient(objectKey);
			BinaryData data = blob.downloadContent();
			String contentType = blob.getProperties().getContentType();
			return Optional.of(new StoredObject(data.toBytes(),
					contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream"));
		}
		catch (BlobStorageException ex) {
			if (isNotFound(ex)) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	@Override
	public void delete(String objectKey) throws Exception {
		container.getBlobClient(objectKey).deleteIfExists();
	}

	@Override
	public List<ObjectInfo> list(String keyPrefix) throws Exception {
		List<ObjectInfo> objects = new ArrayList<>();
		try {
			for (BlobItem item : container.listBlobs(new ListBlobsOptions().setPrefix(keyPrefix), null)) {
				Instant modified = item.getProperties() != null && item.getProperties().getLastModified() != null
						? item.getProperties().getLastModified().toInstant()
						: null;
				objects.add(new ObjectInfo(item.getName(), modified));
			}
		}
		catch (BlobStorageException ex) {
			if (isNotFound(ex)) {
				// Container not created yet — nothing stored, nothing to list.
				return List.of();
			}
			throw ex;
		}
		return objects;
	}

	@Override
	public String presignedDownloadUrl(String objectKey, String fileName) throws Exception {
		BlobClient blob = container.getBlobClient(objectKey);
		BlobServiceSasSignatureValues sas = new BlobServiceSasSignatureValues(
				OffsetDateTime.now().plusMinutes(10),
				new BlobSasPermission().setReadPermission(true))
				.setContentDisposition("attachment; filename=\"" + fileName + "\"");
		return blob.getBlobUrl() + "?" + blob.generateSas(sas);
	}

	private static boolean isNotFound(BlobStorageException ex) {
		return ex.getStatusCode() == 404
				|| BlobErrorCode.BLOB_NOT_FOUND.equals(ex.getErrorCode())
				|| BlobErrorCode.CONTAINER_NOT_FOUND.equals(ex.getErrorCode());
	}
}
