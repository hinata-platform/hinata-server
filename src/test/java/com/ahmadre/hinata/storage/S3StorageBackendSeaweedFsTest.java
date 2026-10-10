package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.config.HinataProperties;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The S3 backend against the store the own instances run (HIN-134): SeaweedFS started by
 * {@code deploy/seaweedfs/entrypoint.sh}, the same script production uses, with credentials
 * required and SSE-S3 under a key from the environment.
 *
 * <p>What is worth proving against the real thing: that every object hinata writes or copies is
 * stored encrypted even in a bucket without a default (the test sets none), that a copy
 * of an encrypted object reads back, that a presigned link works, and that nobody gets in without
 * the credentials.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3StorageBackendSeaweedFsTest {

	private static final String ACCESS = "hinata";
	private static final String SECRET = "test-secret-0123456789abcdef";
	private static final String ADMIN = "hinata-admin";
	private static final String ADMIN_SECRET = "test-admin-0123456789abcdef";

	@Container
	static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.48")
			.withCopyFileToContainer(MountableFile.forHostPath("deploy/seaweedfs/entrypoint.sh"),
					"/opt/hinata/entrypoint.sh")
			.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "/opt/hinata/entrypoint.sh"))
			.withEnv("HINATA_S3_ACCESS_KEY", ACCESS)
			.withEnv("HINATA_S3_SECRET_KEY", SECRET)
			.withEnv("SEAWEEDFS_ADMIN_ACCESS_KEY", ADMIN)
			.withEnv("SEAWEEDFS_ADMIN_SECRET_KEY", ADMIN_SECRET)
			.withEnv("WEED_S3_SSE_KEK", "6c0b5d2a9f3e4b1c8d7a6e5f40312b1c9d8e7f6a5b4c3d2e1f00112233445566")
			.withEnv("WEED_JWT_SIGNING_KEY", "test-jwt-volume-write")
			.withEnv("WEED_JWT_SIGNING_READ_KEY", "test-jwt-volume-read")
			.withEnv("WEED_JWT_FILER_SIGNING_KEY", "test-jwt-filer-write")
			.withEnv("WEED_JWT_FILER_SIGNING_READ_KEY", "test-jwt-filer-read")
			.withExposedPorts(8333)
			.waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(code -> code == 403)
					.withStartupTimeout(Duration.ofMinutes(2)));

	private static S3StorageBackend backend;
	private static MinioClient owner;

	@BeforeAll
	static void bucket() throws Exception {
		String endpoint = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
		MinioClient admin = MinioClient.builder().endpoint(endpoint).credentials(ADMIN, ADMIN_SECRET).build();
		admin.makeBucket(MakeBucketArgs.builder().bucket("hinata").build());
		owner = MinioClient.builder().endpoint(endpoint).credentials(ACCESS, SECRET).build();
		HinataProperties.Storage storage = new HinataProperties.Storage();
		storage.setEndpoint(endpoint);
		storage.setAccessKey(ACCESS);
		storage.setSecretKey(SECRET);
		storage.setAddressingStyle("path");
		storage.setSse("AES256");
		backend = new S3StorageBackend(storage);
	}

	private static String encryptionOf(String key) throws Exception {
		return owner.statObject(StatObjectArgs.builder().bucket("hinata").object(key).build())
				.headers().get("x-amz-server-side-encryption");
	}

	private static void put(String key, String text) throws Exception {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		backend.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
	}

	@Test
	void everyObjectWrittenIsEncrypted_andReadsBack() throws Exception {
		put("attachments/a.txt", "secret attachment");

		assertThat(encryptionOf("attachments/a.txt")).isEqualTo("AES256");
		assertThat(backend.get("attachments/a.txt")).hasValueSatisfying(stored ->
				assertThat(new String(stored.data(), StandardCharsets.UTF_8)).isEqualTo("secret attachment"));
	}

	@Test
	void aCopyIsEncryptedToo_andReadsBack() throws Exception {
		put("attachments/original.txt", "copied content");

		backend.copy("attachments/original.txt", "attachments/copy.txt");

		assertThat(encryptionOf("attachments/copy.txt")).isEqualTo("AES256");
		assertThat(backend.get("attachments/copy.txt")).hasValueSatisfying(stored ->
				assertThat(new String(stored.data(), StandardCharsets.UTF_8)).isEqualTo("copied content"));
	}

	/**
	 * SeaweedFS writes the copy's LastModified without trailing zeros, which the client
	 * cannot parse; a millisecond ending in zero made one copy in ten fail although it
	 * had been made. Forty copies hit that case with near certainty.
	 */
	@Test
	void everyCopySucceeds_whateverMillisecondItLandsOn() throws Exception {
		put("attachments/source.txt", "many copies");
		for (int i = 0; i < 40; i++) {
			backend.copy("attachments/source.txt", "attachments/copy-" + i + ".txt");
		}
		assertThat(backend.get("attachments/copy-39.txt")).isPresent();
	}

	@Test
	void aPresignedLinkServesTheObject() throws Exception {
		put("attachments/link.txt", "linked content");

		String url = backend.presignedDownloadUrl("attachments/link.txt", "link.txt");
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("linked content");
	}

	@Test
	void withoutCredentialsNothingIsReadable() throws Exception {
		put("attachments/private.txt", "private");
		String plain = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333)
				+ "/hinata/attachments/private.txt";

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(plain)).GET().build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	void anUnknownEncryptionSettingFailsTheStart() {
		assertThat(S3StorageBackend.sseOf("")).isNull();
		assertThat(S3StorageBackend.sseOf(" aes256 ")).isNotNull();
		assertThatThrownBy(() -> S3StorageBackend.sseOf("AES-256"))
				.isInstanceOf(IllegalStateException.class);
	}
}
