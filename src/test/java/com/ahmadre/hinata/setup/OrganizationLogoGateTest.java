package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.TestImages;
import com.ahmadre.hinata.storage.TestUploadGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An uploaded logo passes the upload gate as a {@code LOGO} before it is decoded (HIN-177); what
 * is stored afterwards is the PNG the server drew itself.
 */
class OrganizationLogoGateTest {

	private StorageService storage;
	private OrganizationLogoService logos;

	@BeforeEach
	void setUp() {
		storage = mock(StorageService.class);
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		logos = new OrganizationLogoService(storage, settings, TestUploadGates.open());
	}

	@Test
	void aPayloadThatIsNotAnImageIsRefusedAtTheGateAndNothingIsStored() {
		assertThatThrownBy(() -> logos.store(new MockMultipartFile("file", "logo.png", "image/png",
				TestImages.pdf())))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.upload.refused.signature");

		verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
	}

	@Test
	void aRealLogoIsStoredAsThePngTheServerDrew() {
		String url = logos.store(new MockMultipartFile("file", "logo.jpg", "image/jpeg",
				TestImages.jpegWithExif(64, 32, 1)));

		assertThat(url).startsWith(OrganizationLogoService.INTERNAL_URL_PREFIX);
		verify(storage).putObject(eq(OrganizationLogoService.LOGO_OBJECT_KEY), any(byte[].class), eq("image/png"));
	}
}
