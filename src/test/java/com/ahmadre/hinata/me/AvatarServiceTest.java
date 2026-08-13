package com.ahmadre.hinata.me;

import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The avatar's BlurHash rides in its URL rather than in a response field —
 * every DTO that mentions a person already carries that URL. These pin the two
 * halves of that decision: it is written on upload, and filled in on read for
 * pictures that predate it.
 */
class AvatarServiceTest {

	private StorageService storage;
	private UserRepository users;
	private AvatarService avatars;

	@BeforeEach
	void setUp() {
		storage = mock(StorageService.class);
		users = mock(UserRepository.class);
		when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(storage.isConfigured()).thenReturn(true);
		avatars = new AvatarService(storage, users, new ImagePreviewService());
	}

	private static byte[] png() throws Exception {
		BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(180, 60, 40));
		g.fillRect(0, 0, 600, 600);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String blurHashOf(String url) {
		int at = url.indexOf("bh=");
		return at < 0 ? null
				: URLDecoder.decode(url.substring(at + 3), StandardCharsets.UTF_8);
	}

	@Test
	void uploadedAvatarUrlCarriesItsBlurHash() throws Exception {
		User user = User.builder().id("u1").build();

		String url = avatars.store(user,
				new MockMultipartFile("file", "me.png", "image/png", png()));

		assertThat(url).startsWith("/api/v1/users/u1/avatar?v=");
		// 4×3 components: 1 size flag + 1 maximum + 4 DC + 2 per AC term.
		assertThat(blurHashOf(url)).hasSize(6 + 2 * 11);
		assertThat(user.getAvatarUrl()).isEqualTo(url);
		verify(storage).putObject(eq("avatars/u1.jpg"), any(byte[].class), eq("image/jpeg"));
	}

	@Test
	void anOlderAvatarGetsItsHashOnFirstRead() throws Exception {
		User user = User.builder().id("u1").build();
		user.setAvatarUrl("/api/v1/users/u1/avatar?v=1700000000000");
		when(users.findById("u1")).thenReturn(Optional.of(user));
		when(storage.getObject("avatars/u1.jpg")).thenReturn(
				Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load("u1");

		assertThat(blurHashOf(user.getAvatarUrl())).hasSize(6 + 2 * 11);
		// The cache-buster is untouched: re-stamping it would make every client
		// refetch a picture that has not changed.
		assertThat(user.getAvatarUrl()).startsWith("/api/v1/users/u1/avatar?v=1700000000000&bh=");
		verify(users).save(user);
	}

	@Test
	void anAvatarThatAlreadyHasAHashIsNotRewritten() throws Exception {
		User user = User.builder().id("u1").build();
		user.setAvatarUrl("/api/v1/users/u1/avatar?v=1&bh=LEHV6nWB");
		when(users.findById("u1")).thenReturn(Optional.of(user));
		when(storage.getObject("avatars/u1.jpg")).thenReturn(
				Optional.of(new StorageService.StoredObject(png(), "image/jpeg")));

		avatars.load("u1");

		assertThat(user.getAvatarUrl()).isEqualTo("/api/v1/users/u1/avatar?v=1&bh=LEHV6nWB");
		verify(users, never()).save(any(User.class));
	}

	@Test
	void removingTheAvatarDropsTheUrlAndTheObject() {
		User user = User.builder().id("u1").build();
		user.setAvatarUrl("/api/v1/users/u1/avatar?v=1&bh=LEHV6nWB");

		avatars.remove(user);

		assertThat(user.getAvatarUrl()).isNull();
		verify(storage).delete("avatars/u1.jpg");
	}

	@Test
	void aPayloadThatIsNotAnImageIsRejected() {
		User user = User.builder().id("u1").build();

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> avatars.store(user,
						new MockMultipartFile("file", "x.png", "image/png", "not a png".getBytes())))
				.isInstanceOf(com.ahmadre.hinata.common.ApiException.class);
		verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
	}
}
