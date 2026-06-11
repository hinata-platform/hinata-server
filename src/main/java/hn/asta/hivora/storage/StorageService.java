package hn.asta.hivora.storage;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.config.HivoraProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * S3-compatible object storage (MinIO in dev). Object keys are random UUIDs –
 * user-supplied file names never reach the file system or bucket layout.
 */
@Slf4j
@Service
public class StorageService {

	private final HivoraProperties properties;
	private final MinioClient client;

	public StorageService(HivoraProperties properties) {
		this.properties = properties;
		HivoraProperties.Storage storage = properties.getStorage();
		this.client = storage.getAccessKey().isBlank() ? null
				: MinioClient.builder()
						.endpoint(storage.getEndpoint())
						.credentials(storage.getAccessKey(), storage.getSecretKey())
						.region(storage.getRegion())
						.build();
	}

	public boolean isConfigured() {
		return client != null;
	}

	public String upload(MultipartFile file) {
		requireConfigured();
		HivoraProperties.Storage storage = properties.getStorage();
		String contentType = file.getContentType();
		if (contentType == null || !storage.getAllowedContentTypes().contains(contentType)) {
			throw ApiException.badRequest("File type not allowed");
		}
		if (file.getSize() > (long) storage.getMaxUploadMb() * 1024 * 1024) {
			throw ApiException.badRequest("File exceeds " + storage.getMaxUploadMb() + " MB");
		}
		String objectKey = UUID.randomUUID().toString();
		try (var stream = file.getInputStream()) {
			ensureBucket();
			client.putObject(PutObjectArgs.builder()
					.bucket(storage.getBucket())
					.object(objectKey)
					.contentType(contentType)
					.stream(stream, file.getSize(), -1)
					.build());
			return objectKey;
		}
		catch (Exception ex) {
			log.error("Upload failed: {}", ex.getMessage());
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Storage unavailable");
		}
	}

	public String presignedDownloadUrl(String objectKey, String fileName) {
		requireConfigured();
		try {
			return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
					.method(Method.GET)
					.bucket(properties.getStorage().getBucket())
					.object(objectKey)
					.expiry(10, TimeUnit.MINUTES)
					.extraQueryParams(java.util.Map.of("response-content-disposition",
							"attachment; filename=\"" + fileName.replaceAll("[\"\\\\]", "_") + "\""))
					.build());
		}
		catch (Exception ex) {
			throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Storage unavailable");
		}
	}

	public void delete(String objectKey) {
		requireConfigured();
		try {
			client.removeObject(RemoveObjectArgs.builder()
					.bucket(properties.getStorage().getBucket()).object(objectKey).build());
		}
		catch (Exception ex) {
			log.warn("Deleting object {} failed: {}", objectKey, ex.getMessage());
		}
	}

	private void ensureBucket() throws Exception {
		String bucket = properties.getStorage().getBucket();
		if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
			client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
		}
	}

	private void requireConfigured() {
		if (client == null) {
			throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
					"Object storage is not configured");
		}
	}
}
