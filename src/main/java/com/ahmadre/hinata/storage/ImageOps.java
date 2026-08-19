package com.ahmadre.hinata.storage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * The raster primitives shared by every image the server produces (avatars,
 * attachment and inline-media thumbnails): decode, high-quality downscale and
 * re-encode. Re-encoding from a clean {@link BufferedImage} also strips all
 * EXIF/metadata, so a photo's GPS coordinates never travel with a thumbnail.
 *
 * <p>Downscaling uses bicubic interpolation with progressive halving — the
 * best-quality approach for large ratios, and the reason a 4000px photo becomes
 * a sharp 480px thumbnail rather than an aliased one.
 *
 * <p>Every stream here is a memory-cached one, built by hand rather than through
 * {@code ImageIO.createImage*Stream}. Those factories honour
 * {@link ImageIO#getUseCache()}, which defaults to <em>true</em> and spools the
 * whole payload through a temp file in {@code java.io.tmpdir} — so decoding a
 * 7 MB screenshot wrote 7 MB to disk and read it back before a single pixel was
 * produced (measured: 84 ms with the file cache against 45 ms without, for
 * byte-identical output). The bytes are already on the heap in every caller
 * here, so the disk round trip buys nothing. Setting the global flag instead
 * would not do: {@code ImageIO}'s cache setting is per thread group, so a
 * request thread need not see what start-up set.
 */
public final class ImageOps {

	private ImageOps() {
	}

	/**
	 * Upper bound on the pixels a single decode may allocate (~100 MP ≈ 400 MB as
	 * ARGB). A small file can declare enormous dimensions — a "decompression
	 * bomb" — and the header is read before any pixels are, so an image that
	 * would not fit is refused instead of taking the heap down with it.
	 */
	public static final long MAX_DECODE_PIXELS = 100_000_000L;

	/**
	 * Decodes image bytes, or null when they are not a readable raster image or
	 * declare more than {@link #MAX_DECODE_PIXELS} pixels. Returning null rather
	 * than throwing is deliberate: the JDK ships no webp reader, and an
	 * unreadable payload must degrade to "no preview", never to a failed upload.
	 */
	public static BufferedImage read(byte[] bytes) {
		return read(bytes, MAX_DECODE_PIXELS);
	}

	/**
	 * As {@link #read(byte[])} but with a caller-chosen pixel ceiling, for paths
	 * that can afford far less than the global bound — a small file may still
	 * declare enormous dimensions, and a caller serving many reads concurrently
	 * pays for every one of them.
	 */
	public static BufferedImage read(byte[] bytes, long maxPixels) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
				if (pixels <= 0 || pixels > Math.min(maxPixels, MAX_DECODE_PIXELS)) {
					return null;
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	/** True when the image has an alpha channel that a JPEG would flatten away. */
	public static boolean hasAlpha(BufferedImage image) {
		return image.getColorModel().hasAlpha();
	}

	/**
	 * Downscales so neither edge exceeds [maxEdge], preserving the aspect ratio.
	 * Never upscales: a source already within the bound is returned unchanged.
	 */
	public static BufferedImage scaleWithin(BufferedImage src, int maxEdge, boolean keepAlpha) {
		int longest = Math.max(src.getWidth(), src.getHeight());
		if (longest <= maxEdge) {
			return src;
		}
		double ratio = (double) maxEdge / longest;
		int width = Math.max(1, (int) Math.round(src.getWidth() * ratio));
		int height = Math.max(1, (int) Math.round(src.getHeight() * ratio));
		return scaleTo(src, width, height, keepAlpha);
	}

	/** Downscales to exactly [width]×[height] (bicubic, progressive halving). */
	public static BufferedImage scaleTo(BufferedImage src, int width, int height, boolean keepAlpha) {
		BufferedImage current = src;
		int w = src.getWidth();
		int h = src.getHeight();
		while (w / 2 >= width && h / 2 >= height) {
			w = Math.max(1, w / 2);
			h = Math.max(1, h / 2);
			current = draw(current, w, h, keepAlpha, false);
		}
		return draw(current, width, height, keepAlpha, !keepAlpha);
	}

	/**
	 * Draws [src] into a new [w]×[h] surface. Without alpha the target is opaque
	 * RGB; [flattenWhite] fills it first so a transparent source encodes cleanly
	 * to JPEG (which has no alpha channel) instead of turning black.
	 */
	private static BufferedImage draw(BufferedImage src, int w, int h, boolean keepAlpha,
			boolean flattenWhite) {
		BufferedImage out = new BufferedImage(w, h,
				keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		if (flattenWhite) {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, w, h);
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	/** Encodes as JPEG at [quality] (0..1). */
	public static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/**
	 * Encodes as PNG — used when the source's transparency has to survive.
	 * Spelled out rather than {@code ImageIO.write}, which would go back to the
	 * temp-file-cached output stream this class avoids; the written bytes are
	 * identical either way.
	 */
	public static byte[] encodePng(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(image);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}
}
