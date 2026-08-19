package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Azure backend's URL building, which is pure signing — no network I/O
 * happens at construction time or while a SAS is computed, so this runs as a
 * plain unit test like {@link StorageServiceTest}.
 */
class AzureBlobStorageBackendTest {

	private static AzureBlobStorageBackend backend() {
		HinataProperties.Storage storage = new HinataProperties().getStorage();
		storage.setProvider("azure");
		storage.setBucket("hinata");
		storage.setAzureConnectionString("DefaultEndpointsProtocol=https;AccountName=unittest;AccountKey="
				+ Base64.getEncoder().encodeToString("test-key".getBytes(StandardCharsets.UTF_8))
				+ ";EndpointSuffix=core.windows.net");
		return new AzureBlobStorageBackend(storage);
	}

	/**
	 * A copy's source URL has to carry its own read signature.
	 *
	 * <p>The account key on the client authorizes the write at the destination;
	 * the source is fetched by the blob service over HTTP as an ordinary reader,
	 * and this container is private — which is the whole reason downloads are
	 * handed out as SAS URLs. An unsigned source URL is answered with a 404, and
	 * because {@link StorageService#copyObject} turns every failure into "this file
	 * could not be copied", the result would be clones that quietly arrive with no
	 * attachments at all on every Azure installation.
	 */
	@Test
	void aCopySourceUrlIsSignedForReading() {
		String url = backend().signedSourceUrl("8f14e45f-ceea-467a-9e02-cd3f45d3f0e1");

		assertThat(url).contains("sig="); // signed at all
		assertThat(url).contains("sp=r"); // and only for reading
		assertThat(url).contains("se="); // and only for a while
	}

	/**
	 * And it stays inside this account's own container: the URL is built from the
	 * configured endpoint plus the key, never from anything a caller sends, so the
	 * copy cannot be pointed at a host of somebody else's choosing.
	 */
	@Test
	void aCopySourceUrlPointsAtThisContainer() {
		String url = backend().signedSourceUrl("8f14e45f-ceea-467a-9e02-cd3f45d3f0e1");

		assertThat(url).startsWith(
				"https://unittest.blob.core.windows.net/hinata/8f14e45f-ceea-467a-9e02-cd3f45d3f0e1?");
	}
}
