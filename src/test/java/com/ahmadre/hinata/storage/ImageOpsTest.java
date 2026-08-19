package com.ahmadre.hinata.storage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raster primitives every image path shares. What is pinned here is that
 * decode and re-encode are pure heap work: {@code ImageIO}'s stream factories
 * spool the whole payload through a temp file by default, which on the
 * attachment-read path meant writing megabytes to {@code /tmp} and reading them
 * back before a single pixel was produced.
 */
class ImageOpsTest {

	private static BufferedImage image(int width, int height, boolean alpha) {
		BufferedImage img = new BufferedImage(width, height,
				alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(alpha ? new Color(200, 40, 60, 128) : new Color(30, 120, 200));
		g.fillRect(0, 0, width / 2, height);
		g.dispose();
		return img;
	}

	private static byte[] png(BufferedImage img) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	@Test
	void decodeAndReEncodeNeedNoImageIoDiskCache() throws Exception {
		// Point ImageIO's cache at a directory and then take it away. Anything
		// still going through the factories would fail to create its temp file;
		// these primitives build their own memory-cached streams, so they do not
		// notice. That is not a detail — it is a megabyte of disk traffic per
		// attachment read, on a path several agents walk in a loop.
		byte[] source = png(image(600, 400, false));
		BufferedImage transparent = image(120, 80, true);
		Path gone = Files.createTempDirectory("imageops-cache");
		ImageIO.setCacheDirectory(gone.toFile());
		Files.delete(gone);
		try {
			BufferedImage decoded = ImageOps.read(source);
			assertThat(decoded).isNotNull();
			assertThat(decoded.getWidth()).isEqualTo(600);

			byte[] jpeg = ImageOps.encodeJpeg(ImageOps.scaleTo(decoded, 300, 200, false), 0.8f);
			byte[] pngBytes = ImageOps.encodePng(transparent);

			assertThat(ImageOps.read(jpeg).getWidth()).isEqualTo(300);
			assertThat(ImageOps.read(pngBytes).getWidth()).isEqualTo(120);
		}
		finally {
			ImageIO.setCacheDirectory(null);
		}
	}

	@Test
	void pngEncodingKeepsTheAlphaChannel() throws Exception {
		// encodePng drives the writer directly rather than calling ImageIO.write,
		// which would go back to the cached output stream. The bytes must stay
		// what they were: a PNG that still carries transparency.
		byte[] encoded = ImageOps.encodePng(image(80, 60, true));

		BufferedImage decoded = ImageOps.read(encoded);
		assertThat(decoded).isNotNull();
		assertThat(ImageOps.hasAlpha(decoded)).isTrue();
		assertThat(decoded.getWidth()).isEqualTo(80);
		assertThat(decoded.getHeight()).isEqualTo(60);
	}

	@Test
	void aDeclaredPixelCountOverTheCallersCeilingIsRefusedBeforeDecoding() throws Exception {
		byte[] source = png(image(600, 400, false));

		// The header says 240 000 pixels; a caller that can only afford 1 000
		// gets nothing rather than a raster it did not budget for.
		assertThat(ImageOps.read(source, 1_000)).isNull();
		assertThat(ImageOps.read(source, 1_000_000)).isNotNull();
	}
}
