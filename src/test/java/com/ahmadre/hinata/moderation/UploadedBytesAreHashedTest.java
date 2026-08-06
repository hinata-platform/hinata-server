package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.moderation.freeze.FreezeFixtures;
import com.ahmadre.hinata.moderation.image.KnownIllegalHashProvider;
import com.ahmadre.hinata.setup.OrganizationLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The known-illegal hash tier sees the bytes that were <b>uploaded</b>, on the two
 * surfaces that re-encode before storing.
 *
 * <p>{@code AvatarService} recompresses to JPEG and {@code OrganizationLogoService}
 * normalises to PNG, and both used to hand the re-encoded result to the whole
 * pipeline. For a classifier that is right: the stored image is what colleagues
 * will see, and it is the version certain to be a decodable raster. For an
 * <em>exact-hash</em> programme it is a silent disabling — the digest is of a file
 * an accredited body adjudicated, and a JPEG this server produced a millisecond ago
 * is not that file, whatever went in. The check ran, cost a metered call, and could
 * not have matched.
 *
 * <p>Perceptual providers were unaffected, which is exactly why nobody would have
 * noticed. So this test uses a provider that matches on an <b>exact digest</b> —
 * the shape that could not work — and asserts the upload is refused.
 *
 * <p>The images are generated rather than fixtures: what is being asserted is that
 * the array reaching the provider is the same array that arrived, and a fixture
 * would only make it harder to see that.
 */
class UploadedBytesAreHashedTest {

	/**
	 * A provider that matches one exact SHA-256, and records what it was asked
	 * about.
	 *
	 * <p>Test-only, and it does not implement the port that
	 * {@code ModerationWiringTest.noKnownIllegalHashProviderImplementationShips}
	 * forbids shipping — that check scans {@code src/main}, which is the artefact
	 * argument it makes. An operator wiring their own credentialed provider is the
	 * intended use, and this stands in for one.
	 */
	private static final class ExactDigestProvider implements KnownIllegalHashProvider {

		private final String forbidden;
		private final AtomicReference<byte[]> lastSeen = new AtomicReference<>();

		ExactDigestProvider(byte[] forbidden) {
			this.forbidden = sha256(forbidden);
		}

		@Override
		public String id() {
			return "exact-digest-test";
		}

		@Override
		public boolean available() {
			return true;
		}

		@Override
		public Optional<HashMatch> match(byte[] data, String contentType) {
			lastSeen.set(data);
			return forbidden.equals(sha256(data))
					? Optional.of(new HashMatch(id(), "ref-1"))
					: Optional.empty();
		}

		private static String sha256(byte[] data) {
			try {
				return HexFormat.of().formatHex(
						java.security.MessageDigest.getInstance("SHA-256").digest(data));
			}
			catch (java.security.NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	@Test
	void anAvatarIsHashedAsUploadedRatherThanAsRecompressed() throws Exception {
		byte[] uploaded = png(1200, Color.RED);
		ExactDigestProvider provider = new ExactDigestProvider(uploaded);
		AvatarService avatars = new AvatarService(mock(StorageService.class),
				mock(UserRepository.class), moderation(provider), mock(ModerationRecorder.class),
				FreezeFixtures.nothingFrozen());

		assertThatThrownBy(() -> avatars.store(User.builder().id("u-1").displayName("Ada").build(),
				new MockMultipartFile("file", "me.png", "image/png", uploaded)))
				.isInstanceOf(ModerationException.class);
		assertThat(provider.lastSeen.get())
				.as("the provider must be shown the bytes that arrived, not the server's re-encode")
				.isEqualTo(uploaded);
	}

	@Test
	void theOrganisationLogoIsHashedAsUploadedRatherThanAsNormalised() throws Exception {
		// Above MAX_EDGE so normalisation certainly rewrites the bytes. A small solid
		// PNG survives ImageIO's round trip byte-for-byte, which would let this pass
		// against the very defect it exists to catch.
		byte[] uploaded = png(1200, Color.BLUE);
		ExactDigestProvider provider = new ExactDigestProvider(uploaded);
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		OrganizationLogoService logos = new OrganizationLogoService(mock(StorageService.class),
				settings, moderation(provider), mock(ModerationRecorder.class));

		assertThatThrownBy(() -> logos.store(
				new MockMultipartFile("file", "logo.png", "image/png", uploaded)))
				.isInstanceOf(ModerationException.class);
		assertThat(provider.lastSeen.get()).isEqualTo(uploaded);
	}

	/**
	 * The classifier still judges the stored image. Losing that would trade one hole
	 * for another: the re-encode is what readers see and the only version guaranteed
	 * decodable.
	 */
	@Test
	void theClassifierStillJudgesTheStoredReEncoding() throws Exception {
		byte[] uploaded = png(1200, Color.GREEN);
		RecordingImageModerator classifier = new RecordingImageModerator();
		AvatarService avatars = new AvatarService(mock(StorageService.class),
				mock(UserRepository.class),
				new ModerationService(policy(), mock(ModerationRecorder.class),
						List.of(), List.of(classifier), List.of(), List.of()),
				mock(ModerationRecorder.class), FreezeFixtures.nothingFrozen());

		avatars.store(User.builder().id("u-1").displayName("Ada").build(),
				new MockMultipartFile("file", "me.png", "image/png", uploaded));

		assertThat(classifier.seen).isNotEmpty();
		assertThat(classifier.seen.get(0))
				.as("the classifier judges the JPEG that will actually be served")
				.isNotEqualTo(uploaded);
	}

	private static final class RecordingImageModerator
			implements com.ahmadre.hinata.moderation.image.ImageModerator {

		private final List<byte[]> seen = new ArrayList<>();

		@Override
		public String id() {
			return "recording";
		}

		@Override
		public boolean available() {
			return true;
		}

		@Override
		public boolean supports(String contentType) {
			return true;
		}

		@Override
		public java.util.Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
			seen.add(data);
			return java.util.Map.of();
		}
	}

	private static ModerationService moderation(KnownIllegalHashProvider provider) {
		return new ModerationService(policy(), mock(ModerationRecorder.class), List.of(), List.of(),
				List.of(provider), List.of());
	}

	/**
	 * The stock policy over stock settings.
	 *
	 * <p>{@code new ModerationPolicy(null, null)} is enough for the two hash tests —
	 * a match short-circuits before any setting is read — and NPEs the moment a
	 * classifier runs, which is exactly the third test. One shared helper rather than
	 * a policy that happens to work for two of three.
	 */
	private static ModerationPolicy policy() {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		return new ModerationPolicy(settings, new com.ahmadre.hinata.config.HinataProperties());
	}

	/**
	 * A deterministic PNG that no re-encoding reproduces byte-for-byte.
	 *
	 * <p>Both surfaces are sized above their normalisation ceiling (avatar 512,
	 * logo 1024) so the stored image is genuinely a different file, and the content
	 * is a per-pixel gradient rather than a flat fill — a small solid rectangle
	 * survives ImageIO's round trip unchanged, which would make "the provider saw the
	 * uploaded bytes" true by accident on the exact defect being tested.
	 */
	private static byte[] png(int size, Color colour) throws Exception {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				image.setRGB(x, y, colour.getRGB() ^ ((x * 31 + y * 17) & 0x00FFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
