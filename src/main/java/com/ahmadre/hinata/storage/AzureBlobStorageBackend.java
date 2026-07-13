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
