package com.ahmadre.hinata.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;

/**
 * Rasterizes the first page of a PDF, so a document attachment gets a real
 * preview — the page itself — instead of a generic file glyph.
 *
 * <p>Only page one, and only at the resolution a thumbnail needs: rendering is
 * the expensive half of previewing a PDF, and everything past the first page is
 * work nobody sees. The result then goes through the same downscale + BlurHash
 * path as an uploaded picture.
 *
 * <p>A PDF is untrusted input, and PDFBox is a parser of a famously permissive
 * format. Three things bound what a hostile file can cost: the page is rendered
 * at a scale derived from its own size (so a poster-sized media box does not
 * become a gigapixel raster), the render is refused outright above
 * {@link ImageOps#MAX_DECODE_PIXELS}, and any failure — malformed, encrypted,
 * out of memory — returns null rather than propagating. A file without a
 * preview is a file with a glyph, which is where this feature started.
 */
@Slf4j
final class PdfPageRenderer {

	/** Longest edge of the rendered page, in pixels, before downscaling. */
	private static final float TARGET_EDGE = ImagePreviewService.THUMBNAIL_MAX_EDGE;

	/** Never magnify a tiny page beyond this; ×4 already looks like a scan. */
	private static final float MAX_SCALE = 4f;

	private PdfPageRenderer() {
	}

	/** The first page as an image, or null when it cannot be rendered. */
	static BufferedImage firstPage(byte[] pdf) {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			if (document.getNumberOfPages() < 1) {
				return null;
			}
			PDPage page = document.getPage(0);
			float width = page.getCropBox().getWidth();
			float height = page.getCropBox().getHeight();
			if (width <= 0 || height <= 0) {
				return null;
			}
			float scale = Math.min(MAX_SCALE, TARGET_EDGE / Math.max(width, height));
			if ((long) (width * scale) * (long) (height * scale) > ImageOps.MAX_DECODE_PIXELS) {
				return null;
			}
			// White background: a PDF page is paper, and rendering onto
			// transparency would turn every unpainted area black in a JPEG.
			return new PDFRenderer(document).renderImage(0, scale, ImageType.RGB);
		}
		catch (Exception | OutOfMemoryError ex) {
			log.warn("PDF page render failed: {}", ex.toString());
			return null;
		}
	}
}
