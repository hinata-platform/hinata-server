package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend selection is pure configuration wiring — no network I/O happens at
 * construction time for either SDK, so this can run as a plain unit test.
 */
class StorageServiceTest {

	private static HinataProperties properties() {
		return new HinataProperties();
	}

	@Test
	void unconfiguredS3IsNotConfigured() {
		StorageService service = new StorageService(properties());
		assertThat(service.isConfigured()).isFalse();
		assertThatThrownBy(() -> service.getObject("media/x")).isInstanceOf(ApiException.class);
	}

	@Test
	void s3CredentialsConfigureTheS3Backend() {
		HinataProperties props = properties();
		props.getStorage().setAccessKey("key");
		props.getStorage().setSecretKey("secret");
		assertThat(new StorageService(props).isConfigured()).isTrue();
	}

	@Test
	void explicitAddressingStylesAreAccepted() {
		for (String style : new String[] {"auto", "virtual-host", "path"}) {
			HinataProperties props = properties();
			props.getStorage().setAccessKey("key");
			props.getStorage().setSecretKey("secret");
			props.getStorage().setAddressingStyle(style);
			assertThat(new StorageService(props).isConfigured()).isTrue();
		}
	}

	@Test
	void azureWithoutConnectionStringIsNotConfigured() {
		HinataProperties props = properties();
		props.getStorage().setProvider("azure");
		// S3 credentials must not accidentally count for the azure provider.
		props.getStorage().setAccessKey("key");
		props.getStorage().setSecretKey("secret");
		assertThat(new StorageService(props).isConfigured()).isFalse();
	}

	@Test
	void azureConnectionStringConfiguresTheAzureBackend() {
		HinataProperties props = properties();
		props.getStorage().setProvider("azure");
		props.getStorage().setAzureConnectionString(
				"DefaultEndpointsProtocol=https;AccountName=unittest;AccountKey="
						+ java.util.Base64.getEncoder().encodeToString("test-key".getBytes())
						+ ";EndpointSuffix=core.windows.net");
		assertThat(new StorageService(props).isConfigured()).isTrue();
	}

	@Test
	void unknownProviderFailsFastAtStartup() {
		HinataProperties props = properties();
		props.getStorage().setProvider("ftp");
		assertThatThrownBy(() -> new StorageService(props))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ftp");
	}
}
