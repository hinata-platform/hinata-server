package com.ahmadre.hinata.project;

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
 * A project's avatar behaves like a person's: one deterministic object per
 * project, a URL that carries both a cache-buster and the BlurHash placeholder,
 * and a hash filled in on first read for pictures that predate it.
 */
class ProjectAvatarServiceTest {

	private StorageService storage;
	private ProjectRepository projects;
	private ProjectAvatarService avatars;

	@BeforeEach
	void setUp() {
		storage = mock(StorageService.class);
		projects = mock(ProjectRepository.class);
		when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(storage.isConfigured()).thenReturn(true);
		avatars = new ProjectAvatarService(storage, projects, new ImagePreviewService());
	}

	private static Project project() {
		return Project.builder().id("p1").key("HIN").name("Hinata").build();
	}

	private static byte[] png() throws Exception {
		BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(200, 140, 40));
		g.fillRect(0, 0, 600, 600);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static MockMultipartFile upload() throws Exception {
		return new MockMultipartFile("file", "logo.png", "image/png", png());
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
	void uploadingStoresOneObjectPerProjectAndReturnsAHashedUrl() throws Exception {
		Project project = project();

		String url = avatars.store(project, upload());

		assertThat(url).startsWith("/api/v1/projects/p1/avatar?v=");
		// 4×3 components: 1 size flag + 1 maximum + 4 DC + 2 per AC term.
		assertThat(blurHashOf(url)).hasSize(6 + 2 * 11);
		assertThat(project.getAvatarUrl()).isEqualTo(url);
		verify(storage).putObject(eq("projects/p1.jpg"), any(byte[].class), eq("image/jpeg"));
	}

	/**
	 * Replacing a picture overwrites the same key, so the URL is the only thing
	 * that can tell a client the bytes changed — its {@code ?v=} token has to move
	 * or every client keeps showing the old logo from cache for a week.
	 */
	@Test
	void replacingThePictureMovesTheCacheBuster() throws Exception {
		Project project = project();

		String first = avatars.store(project, upload());
		Thread.sleep(2);
		String second = avatars.store(project, upload());

		assertThat(versionOf(second)).isNotEqualTo(versionOf(first));
		assertThat(second).isNotEqualTo(first);
	}

	@Test
	void removingDropsTheUrlAndTheObject() {
		Project project = project();
		project.setAvatarUrl("/api/v1/projects/p1/avatar?v=1&bh=LEHV6nWB");

		avatars.remove(project);

		assertThat(project.getAvatarUrl()).isNull();
		verify(storage).delete("projects/p1.jpg");
	}

	@Test
	void anOlderAvatarGetsItsHashOnFirstRead() throws Exception {
		Project project = project();
		project.setAvatarUrl("/api/v1/projects/p1/avatar?v=1700000000000");
		when(storage.getObject("projects/p1.jpg"))
				.thenReturn(Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load(project);

		assertThat(blurHashOf(project.getAvatarUrl())).hasSize(6 + 2 * 11);
		// The cache-buster is untouched: re-stamping it would make every client
		// refetch a picture that has not changed.
		assertThat(project.getAvatarUrl())
				.startsWith("/api/v1/projects/p1/avatar?v=1700000000000&bh=");
		verify(projects).save(project);
	}

	@Test
	void anAvatarThatAlreadyHasAHashIsNotRewritten() throws Exception {
		Project project = project();
		project.setAvatarUrl("/api/v1/projects/p1/avatar?v=1&bh=LEHV6nWB");
		when(storage.getObject("projects/p1.jpg"))
				.thenReturn(Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load(project);

		assertThat(project.getAvatarUrl()).isEqualTo("/api/v1/projects/p1/avatar?v=1&bh=LEHV6nWB");
		verify(projects, never()).save(any(Project.class));
	}

	@Test
	void aPayloadThatIsNotAnImageIsRejectedAndNothingIsStored() {
		Project project = project();

		assertThatThrownBy(() -> avatars.store(project,
				new MockMultipartFile("file", "x.png", "image/png", "not a png".getBytes())))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.unreadable");

		verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
		assertThat(project.getAvatarUrl()).isNull();
	}

	/** An object store that is not configured must not fail a project deletion. */
	@Test
	void cleanupIsSkippedWhenNoObjectStoreIsConfigured() {
		when(storage.isConfigured()).thenReturn(false);

		avatars.deleteStoredObject("p1");

		verify(storage, never()).delete(anyString());
	}
}
