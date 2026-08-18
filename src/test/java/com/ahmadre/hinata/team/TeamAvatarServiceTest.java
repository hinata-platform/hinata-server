package com.ahmadre.hinata.team;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
 * A team's avatar behaves like a person's: one deterministic object per team,
 * a URL that carries both a cache-buster and the BlurHash placeholder, and a
 * hash filled in on first read for pictures that predate it.
 */
class TeamAvatarServiceTest {

	private StorageService storage;
	private TeamRepository teams;
	private TeamAvatarService avatars;

	@BeforeEach
	void setUp() {
		storage = mock(StorageService.class);
		teams = mock(TeamRepository.class);
		when(teams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(storage.isConfigured()).thenReturn(true);
		avatars = new TeamAvatarService(storage, teams, new ImagePreviewService());
	}

	private static Team team() {
		return Team.builder().id("t1").key("CORE").name("Core").build();
	}

	private static byte[] png() throws Exception {
		BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(40, 120, 90));
		g.fillRect(0, 0, 600, 600);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static MockMultipartFile upload() throws Exception {
		return new MockMultipartFile("file", "crest.png", "image/png", png());
	}

	private static String blurHashOf(String url) {
		int at = url.indexOf("bh=");
		return at < 0 ? null : URLDecoder.decode(url.substring(at + 3), StandardCharsets.UTF_8);
	}

	private static String versionOf(String url) {
		int at = url.indexOf("?v=");
		int end = url.indexOf('&', at);
		return end < 0 ? url.substring(at + 3) : url.substring(at + 3, end);
	}

	@Test
	void uploadingStoresOneObjectPerTeamAndReturnsAHashedUrl() throws Exception {
		Team team = team();

		String url = avatars.store(team, upload());

		assertThat(url).startsWith("/api/v1/teams/t1/avatar?v=");
		// 4×3 components: 1 size flag + 1 maximum + 4 DC + 2 per AC term.
		assertThat(blurHashOf(url)).hasSize(6 + 2 * 11);
		assertThat(team.getAvatarUrl()).isEqualTo(url);
		verify(storage).putObject(eq("teams/t1.jpg"), any(byte[].class), eq("image/jpeg"));
	}

	/**
	 * Replacing a picture overwrites the same key, so the URL is the only thing
	 * that can tell a client the bytes changed — its {@code ?v=} token has to move
	 * or every client keeps showing the old crest from cache for a week.
	 */
	@Test
	void replacingThePictureMovesTheCacheBuster() throws Exception {
		Team team = team();

		String first = avatars.store(team, upload());
		Thread.sleep(2);
		String second = avatars.store(team, upload());

		assertThat(versionOf(second)).isNotEqualTo(versionOf(first));
		assertThat(second).isNotEqualTo(first);
	}

	@Test
	void removingDropsTheUrlAndTheObject() {
		Team team = team();
		team.setAvatarUrl("/api/v1/teams/t1/avatar?v=1&bh=LEHV6nWB");

		avatars.remove(team);

		assertThat(team.getAvatarUrl()).isNull();
		verify(storage).delete("teams/t1.jpg");
	}

	@Test
	void anOlderAvatarGetsItsHashOnFirstRead() throws Exception {
		Team team = team();
		team.setAvatarUrl("/api/v1/teams/t1/avatar?v=1700000000000");
		when(storage.getObject("teams/t1.jpg"))
				.thenReturn(Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load(team);

		assertThat(blurHashOf(team.getAvatarUrl())).hasSize(6 + 2 * 11);
		// The cache-buster is untouched: re-stamping it would make every client
		// refetch a picture that has not changed.
		assertThat(team.getAvatarUrl()).startsWith("/api/v1/teams/t1/avatar?v=1700000000000&bh=");
		verify(teams).save(team);
	}

	@Test
	void anAvatarThatAlreadyHasAHashIsNotRewritten() throws Exception {
		Team team = team();
		team.setAvatarUrl("/api/v1/teams/t1/avatar?v=1&bh=LEHV6nWB");
		when(storage.getObject("teams/t1.jpg"))
				.thenReturn(Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load(team);

		assertThat(team.getAvatarUrl()).isEqualTo("/api/v1/teams/t1/avatar?v=1&bh=LEHV6nWB");
		verify(teams, never()).save(any(Team.class));
	}

	@Test
	void aPayloadThatIsNotAnImageIsRejectedAndNothingIsStored() {
		Team team = team();

		assertThatThrownBy(() -> avatars.store(team,
				new MockMultipartFile("file", "x.png", "image/png", "not a png".getBytes())))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.unreadable");

		verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
		assertThat(team.getAvatarUrl()).isNull();
	}

	/** An object store that is not configured must not fail a team deletion. */
	@Test
	void cleanupIsSkippedWhenNoObjectStoreIsConfigured() {
		when(storage.isConfigured()).thenReturn(false);

		avatars.deleteStoredObject("t1");

		verify(storage, never()).delete(anyString());
	}
}
