package com.ahmadre.hinata.storage;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

class ImagePreviewServiceTest {

	private final ImagePreviewService previews = new ImagePreviewService();

	private static byte[] png(int width, int height, boolean transparent) throws Exception {
		BufferedImage image = new BufferedImage(width, height,
				transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		if (!transparent) {
			g.setColor(new Color(30, 120, 200));
			g.fillRect(0, 0, width, height);
		}
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	@Test
	void thumbnailFitsTheBoundAndKeepsTheAspectRatio() throws Exception {
		Optional<ImagePreviewService.Preview> preview = previews.create(png(1600, 800, false));

		assertThat(preview).isPresent();
		BufferedImage thumbnail = ImageOps.read(preview.get().bytes());
		assertThat(thumbnail).isNotNull();
		assertThat(thumbnail.getWidth()).isEqualTo(ImagePreviewService.THUMBNAIL_MAX_EDGE);
		assertThat(thumbnail.getHeight()).isEqualTo(ImagePreviewService.THUMBNAIL_MAX_EDGE / 2);
		assertThat(preview.get().contentType()).isEqualTo("image/jpeg");
		// A thumbnail that is not dramatically smaller than the source is not
		// doing its job; this is the whole reason the endpoint exists.
		assertThat(preview.get().bytes().length).isLessThan(png(1600, 800, false).length);
	}

	@Test
	void smallImagesAreNotUpscaled() throws Exception {
		Optional<ImagePreviewService.Preview> preview = previews.create(png(64, 40, false));

		assertThat(preview).isPresent();
		BufferedImage thumbnail = ImageOps.read(preview.get().bytes());
		assertThat(thumbnail.getWidth()).isEqualTo(64);
		assertThat(thumbnail.getHeight()).isEqualTo(40);
	}

	@Test
	void transparencySurvivesAsPng() throws Exception {
		Optional<ImagePreviewService.Preview> preview = previews.create(png(800, 800, true));

		assertThat(preview).isPresent();
		// JPEG would flatten the transparent areas to white and flash against the
		// app's dark surfaces the moment the real image arrives.
		assertThat(preview.get().contentType()).isEqualTo("image/png");
		assertThat(ImageOps.hasAlpha(ImageOps.read(preview.get().bytes()))).isTrue();
	}

	@Test
	void everyPreviewCarriesABlurHash() throws Exception {
		Optional<ImagePreviewService.Preview> preview = previews.create(png(300, 200, false));

		assertThat(preview).isPresent();
		assertThat(preview.get().blurHash()).hasSize(6 + 2 * 11);
	}

	@Test
	void undecodableBytesYieldNoPreviewInsteadOfFailing() {
		// A PDF, a webp (no JDK reader) or a truncated upload must degrade to "no
		// preview" — the caller then serves the original, as it always did.
		assertThat(previews.create("%PDF-1.7 not an image".getBytes())).isEmpty();
		assertThat(previews.create(new byte[0])).isEmpty();
		assertThat(previews.create(null)).isEmpty();
	}
}
