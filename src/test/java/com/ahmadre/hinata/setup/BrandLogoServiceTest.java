package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The logo as the rest of the server gets it, with the settings, the storage and the fetcher stood in for. */
class BrandLogoServiceTest {

	private static final String ADDRESS = "https://files.example.org/logo";
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
	private static final byte[] NEWER_PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1 };
	private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

	private final SettingsService settings = mock(SettingsService.class);
	private final OrganizationLogoService logos = mock(OrganizationLogoService.class);
	private final LogoFetcher fetcher = mock(LogoFetcher.class);
	private final MovableClock clock = new MovableClock(Instant.parse("2026-09-14T12:00:00Z"));
	private BrandLogoService brandLogo;

	@BeforeEach
	void configureAnExternalLogo() {
		ServerSettings configured = new ServerSettings();
		configured.setOrganizationName("Hinata");
		configured.getGeneral().setLogoUrl(ADDRESS);
		when(settings.get()).thenReturn(configured);
		when(logos.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
		brandLogo = new BrandLogoService(settings, logos, fetcher, clock);
	}

	@Test
	void servesAVectorLogoButDrawsNothingFromIt() {
		when(fetcher.fetchLogo(ADDRESS)).thenReturn(new StorageService.StoredObject(SVG, "image/svg+xml"));

		assertThat(brandLogo.display()).hasValueSatisfying(asset -> assertThat(asset.contentType()).isEqualTo("image/svg+xml"));
		// No decoder in this process is ever pointed at an SVG.
		assertThat(brandLogo.raster()).isEmpty();
		assertThat(brandLogo.configuredButUnusableForDocuments()).isTrue();
		verify(logos, never()).normalize(any());
	}

	@Test
	void keepsTheLastLogoWhenItsHostStopsAnsweringAndAsksAgainAMinuteLater() {
		when(fetcher.fetchLogo(ADDRESS))
				.thenReturn(new StorageService.StoredObject(PNG, "image/png"))
				.thenThrow(ApiException.badRequest("error.media.fetchFailed"))
				.thenReturn(new StorageService.StoredObject(NEWER_PNG, "image/png"));
		assertThat(brandLogo.display()).isPresent();

		// Once the fifteen minutes have passed, the host does not answer, and the last bytes stay.
		clock.advance(Duration.ofMinutes(15).plusSeconds(1));
		assertThat(brandLogo.display()).hasValueSatisfying(asset -> assertThat(asset.bytes()).isEqualTo(PNG));
		assertThat(brandLogo.raster()).contains(PNG);
		// Within the minute nobody asks the host again.
		clock.advance(Duration.ofSeconds(30));
		assertThat(brandLogo.display()).hasValueSatisfying(asset -> assertThat(asset.bytes()).isEqualTo(PNG));
		verify(fetcher, times(2)).fetchLogo(ADDRESS);

		// After it, the host is asked again, and its newer logo is served.
		clock.advance(Duration.ofSeconds(31));
		assertThat(brandLogo.display()).hasValueSatisfying(asset -> assertThat(asset.bytes()).isEqualTo(NEWER_PNG));
		verify(fetcher, times(3)).fetchLogo(ADDRESS);
	}

	/** A clock the test moves on by hand. */
	private static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
