package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.StorageService.ObjectInfo;
import com.ahmadre.hinata.storage.StorageService.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * S3-protocol backend. Covers every S3-compatible store: MinIO (the bundled
 * default), AWS S3, Google Cloud Storage via its S3-interoperable XML API
 * (HMAC keys), Cloudflare R2, DigitalOcean Spaces, Hetzner Object Storage, …
 * Which store is used is purely a matter of endpoint + credentials.
 */
class S3StorageBackend implements StorageBackend {

	private final MinioClient client;
	private final String bucket;

	S3StorageBackend(HinataProperties.Storage storage) {
		this.bucket = storage.getBucket();
		MinioClient built = MinioClient.builder()
				.endpoint(storage.getEndpoint())
				.credentials(storage.getAccessKey(), storage.getSecretKey())
				.region(storage.getRegion())
				.build();
		// "auto" keeps the client's detection: virtual-host style on AWS
		// endpoints, path style everywhere else. Some providers need an
		// explicit override (e.g. GCS interop is happiest with path style,
		// a few S3 gateways require virtual-host style).
		switch (storage.getAddressingStyle()) {
			case "virtual-host" -> built.enableVirtualStyleEndpoint();
			case "path" -> built.disableVirtualStyleEndpoint();
			default -> {
				// auto
			}
		}
		this.client = built;
	}

	@Override
	public void put(String objectKey, InputStream stream, long length, String contentType) throws Exception {
		ensureBucket();
		client.putObject(PutObjectArgs.builder()
				.bucket(bucket)
				.object(objectKey)
				.contentType(contentType)
				.stream(stream, length, -1)
				.build());
	}

	@Override
	public Optional<StoredObject> get(String objectKey) throws Exception {
		try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
				.bucket(bucket)
				.object(objectKey)
				.build())) {
			String contentType = response.headers().get("Content-Type");
			return Optional.of(new StoredObject(response.readAllBytes(),
					contentType != null ? contentType : "application/octet-stream"));
		}
		catch (ErrorResponseException ex) {
			if ("NoSuchKey".equals(ex.errorResponse().code())) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	@Override
	public void delete(String objectKey) throws Exception {
		client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
	}

	@Override
	public List<ObjectInfo> list(String keyPrefix) throws Exception {
		List<ObjectInfo> objects = new ArrayList<>();
		Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
				.bucket(bucket)
				.prefix(keyPrefix)
				.recursive(true)
				.build());
		for (Result<Item> result : results) {
			Item item = result.get();
			Instant modified = item.lastModified() != null ? item.lastModified().toInstant() : null;
			objects.add(new ObjectInfo(item.objectName(), modified));
		}
		return objects;
	}

	@Override
	public String presignedDownloadUrl(String objectKey, String fileName) throws Exception {
		return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
				.method(Method.GET)
				.bucket(bucket)
				.object(objectKey)
				.expiry(10, TimeUnit.MINUTES)
				.extraQueryParams(Map.of("response-content-disposition",
						"attachment; filename=\"" + fileName + "\""))
				.build());
	}

	private void ensureBucket() throws Exception {
		if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
			client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
		}
	}
}
