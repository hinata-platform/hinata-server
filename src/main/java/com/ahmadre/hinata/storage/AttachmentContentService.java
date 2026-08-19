package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a stored attachment into something that can safely be handed to a
 * machine reader — today the MCP tools, tomorrow anything else that has to look
 * <em>inside</em> a file rather than list it.
 *
 * <p>The rules are the same in every case and they are all about bounds. An
 * over-sized file is refused whole (never half-delivered, which would look like
 * data and read like garbage), a picture is always decoded and re-encoded at a
 * bounded width — which also strips its EXIF/GPS — and text is capped at a
 * character budget. Types outside the allow-list get no content at all: a ZIP or
 * an .xlsx has nothing to contribute to a conversation and everything to lose in
 * one.
 *
 * <p>Nothing here knows who is asking. Authorization belongs to the caller, which
 * must already have resolved the attachment through the owning issue's ACL — the
 * object key never leaves this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentContentService {

	/**
	 * The caller's budget: the largest raw object worth reading at all, the
	 * widest picture worth returning, and the longest text worth extracting.
	 */
	public record Limits(long maxBytes, int imageMaxWidth, int textMaxChars) {
	}

	/** Narrowest sensible output: below this a screenshot's text is unreadable. */
	public static final int MIN_IMAGE_WIDTH = 320;

	/** Widest output, whatever a caller asks for — beyond this nothing is gained. */
	public static final int MAX_IMAGE_WIDTH = 2048;

	/**
	 * Pixel ceiling for the returned picture. Width alone does not bound a very
	 * tall image (a full-page screenshot is easily 1600×20000), and the encoder
	 * pays for every pixel.
	 */
	private static final long MAX_OUTPUT_PIXELS = 4_000_000L;

	/**
	 * Byte ceiling for the encoded picture. Base64 inflates by a third, so this
	 * is what keeps a noisy photograph from becoming two megabytes of context.
	 */
	static final int MAX_ENCODED_BYTES = 1_500_000;

	/**
	 * Pixel ceiling for the <em>decode</em>. Well below what the upload path
	 * allows: a 5 MB PNG can legitimately declare a hundred megapixels, which is
	 * 400 MB of heap as ARGB, and this path can be asked to do that again every
	 * few seconds. Thirty megapixels still covers any screenshot or camera photo
	 * anybody attaches to a ticket.
	 */
	private static final long MAX_DECODE_PIXELS = 30_000_000L;

	/** Re-encode attempts allowed after the first, before whatever is in hand wins. */
	private static final int SHRINK_ATTEMPTS = 3;

	/**
	 * How far one shrink step may go, as a fraction of the width it started
	 * from. An encoder's output grows roughly with the pixel count, so the step
	 * is derived from how far over budget the last attempt came out — but only
	 * within these bounds. The upper one guarantees progress (and therefore
	 * termination) when the estimate is optimistic; the lower one stops a single
	 * badly-estimated step from throwing away half the resolution the budget
	 * would in fact have allowed.
	 */
	private static final double MIN_SHRINK = 0.50;
	private static final double MAX_SHRINK = 0.75;

	/**
	 * Aim a little inside the ceiling rather than exactly at it. The estimate is
	 * an approximation, and one that lands a single byte over pays for another
	 * full scale-and-encode pass — where landing ten percent under costs about
	 * five percent of the width.
	 */
	private static final double SHRINK_AIM = 0.90;

	private static final float JPEG_QUALITY = 0.82f;

	/** Pictures worth decoding — mirrors what the upload path accepts. */
	private static final Set<String> IMAGE_TYPES = Set.of(
			"image/png", "image/jpeg", "image/gif", "image/webp");

	/**
	 * Text worth extracting. An allow-list, never a deny-list: an unknown type is
	 * refused rather than guessed at, so a new upload format cannot silently
	 * start streaming binary into a context window.
	 */
	private static final Set<String> TEXT_TYPES = Set.of(
			"text/plain", "text/csv", "text/markdown", "text/xml", "text/yaml",
			"application/json", "application/xml", "application/yaml", "application/x-yaml");

	private static final String PDF_TYPE = "application/pdf";

	/**
	 * The most bytes UTF-8 can spend per decoded {@code char}, counting malformed
	 * input: a three-byte sequence decodes to one character, a four-byte one to a
	 * surrogate pair, and the longest malformed run the JDK folds into a single
	 * replacement character is three bytes. Four leaves a margin over that three.
	 */
	private static final int MAX_BYTES_PER_CHAR = 4;

	private final StorageService storage;

	/**
	 * Renders [attachment] within [limits]. Never throws for a file it cannot
	 * show: an unsupported type, an over-sized file and an undecodable payload
	 * are all answers, not errors — the caller reports the reason and moves on.
	 */
	public AttachmentContent render(Issue.Attachment attachment, Limits limits) {
		String type = normalizeType(attachment.getContentType());
		if (!isRenderable(type)) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.TYPE_NOT_RENDERABLE);
		}
		// The recorded size gates before the object is fetched: refusing a 40 MB
		// file should not cost 40 MB of transfer first.
		if (attachment.getSize() > limits.maxBytes()) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.TOO_LARGE);
		}
		Optional<StorageService.StoredObject> stored = storage.getObject(attachment.getObjectKey());
		if (stored.isEmpty()) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.MISSING);
		}
		byte[] data = stored.get().data();
		// And again on what actually came back: the recorded size is metadata,
		// the bytes in hand are the thing that lands in the context window.
		if (data == null || data.length > limits.maxBytes()) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.TOO_LARGE);
		}
		if (IMAGE_TYPES.contains(type)) {
			return renderImage(data, limits.imageMaxWidth());
		}
		if (PDF_TYPE.equals(type)) {
			return renderPdf(data, limits.textMaxChars());
		}
		return renderText(data, limits.textMaxChars());
	}

	/** Whether this content type has any representation worth returning. */
	public boolean isRenderable(String contentType) {
		String type = normalizeType(contentType);
		return IMAGE_TYPES.contains(type) || PDF_TYPE.equals(type) || TEXT_TYPES.contains(type);
	}

	/**
	 * Strips parameters and case from a content type ({@code "TEXT/CSV;
	 * charset=utf-8"} → {@code "text/csv"}) so the allow-list matches what was
	 * actually uploaded rather than how it was spelled.
	 */
	private static String normalizeType(String contentType) {
		if (contentType == null) {
			return "";
		}
		int parameter = contentType.indexOf(';');
		String base = parameter < 0 ? contentType : contentType.substring(0, parameter);
		return base.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Decodes, downscales and re-encodes. The re-encode is not optional even for
	 * a picture already within bounds: it is what strips the metadata the camera
	 * wrote, and what guarantees the bytes leaving here are a raster this JVM
	 * produced rather than a file somebody uploaded.
	 */
	private AttachmentContent renderImage(byte[] data, int requestedWidth) {
		// The decode is inside the guard, not before it: it is the single largest
		// allocation on this path (a bounded 30 MP raster is still ~120 MB as
		// ARGB), so it is also the step most likely to fail with an Error rather
		// than the exception ImageOps already turns into a null.
		try {
			BufferedImage source = ImageOps.read(data, MAX_DECODE_PIXELS);
			if (source == null) {
				// No reader (webp), a decompression bomb, or simply broken bytes.
				return new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE);
			}
			boolean alpha = ImageOps.hasAlpha(source);
			int width = targetWidth(source, requestedWidth);
			for (int attempt = 0; ; attempt++) {
				BufferedImage scaled = scaleToWidth(source, width, alpha);
				byte[] encoded = alpha
						? ImageOps.encodePng(scaled)
						: ImageOps.encodeJpeg(scaled, JPEG_QUALITY);
				boolean lastChance = attempt >= SHRINK_ATTEMPTS || scaled.getWidth() <= MIN_IMAGE_WIDTH;
				if (encoded.length <= MAX_ENCODED_BYTES || lastChance) {
					return new AttachmentContent.Image(encoded, alpha ? "image/png" : "image/jpeg",
							scaled.getWidth(), scaled.getHeight(),
							source.getWidth(), source.getHeight());
				}
				width = narrowerThan(scaled.getWidth(), encoded.length);
			}
		}
		catch (Exception | OutOfMemoryError ex) {
			log.warn("Attachment image render failed: {}", ex.toString());
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE);
		}
	}

	/**
	 * The width to render at: the caller's wish clamped to something sensible,
	 * never above the source (upscaling invents nothing), and pulled down further
	 * when the resulting pixel count would exceed {@link #MAX_OUTPUT_PIXELS}.
	 */
	private static int targetWidth(BufferedImage source, int requestedWidth) {
		int width = Math.min(Math.max(requestedWidth, MIN_IMAGE_WIDTH), MAX_IMAGE_WIDTH);
		width = Math.min(width, source.getWidth());
		double aspect = (double) source.getHeight() / source.getWidth();
		if ((long) width * (long) Math.round(width * aspect) > MAX_OUTPUT_PIXELS) {
			width = (int) Math.floor(Math.sqrt(MAX_OUTPUT_PIXELS / aspect));
		}
		return Math.max(1, width);
	}

	/**
	 * The width to try next after an encode came out at [encodedBytes], which was
	 * over {@link #MAX_ENCODED_BYTES}. Every scale-and-encode attempt costs a full
	 * pass over the raster — around 60 ms for a 1600px picture with transparency —
	 * so the step is aimed rather than fixed: bytes grow about linearly with the
	 * pixel count, and pixels grow with the square of the width, so the square
	 * root of "how far over budget" is the width factor that should land inside
	 * it. Clamped to {@link #MIN_SHRINK}…{@link #MAX_SHRINK} because the model is
	 * an approximation, not a promise.
	 *
	 * <p>On a 3.7 MB 1300×850 picture the encoder cannot compress, a fixed
	 * three-quarter step spent three passes (365 ms) to land at 731px; aiming the
	 * step lands at 793px in two (227 ms) — less work <em>and</em> a sharper
	 * answer, since overshooting downward throws away resolution the budget would
	 * in fact have paid for.
	 */
	static int narrowerThan(int width, int encodedBytes) {
		double aimed = Math.sqrt(SHRINK_AIM * MAX_ENCODED_BYTES / encodedBytes);
		double factor = Math.clamp(aimed, MIN_SHRINK, MAX_SHRINK);
		return Math.max(MIN_IMAGE_WIDTH, (int) (width * factor));
	}

	private static BufferedImage scaleToWidth(BufferedImage source, int width, boolean alpha) {
		int height = Math.max(1, (int) Math.round(
				(double) source.getHeight() * width / source.getWidth()));
		return ImageOps.scaleTo(source, width, height, alpha);
	}

	private AttachmentContent renderPdf(byte[] data, int maxChars) {
		// Trust the magic bytes, not the recorded content type: PDFBox is the one
		// parser here that a mislabelled upload could aim at.
		if (!FileSignature.isPdf(data)) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE);
		}
		PdfTextExtractor.Extract extract = PdfTextExtractor.extract(data, maxChars);
		if (extract == null) {
			return new AttachmentContent.Unavailable(AttachmentContent.Reason.UNREADABLE);
		}
		return new AttachmentContent.Text(extract.text(), extract.truncated());
	}

	/**
	 * Decodes as UTF-8 with replacement rather than refusing on the first invalid
	 * byte: a CSV exported from a spreadsheet is routinely not quite UTF-8, and a
	 * replacement character in one cell is a far better answer than none at all.
	 *
	 * <p>Only the prefix that can possibly be kept is decoded. UTF-8 never spends
	 * more than {@link #MAX_BYTES_PER_CHAR} bytes on one {@code char} — a
	 * three-byte sequence yields one, and the four-byte ones yield two — so that
	 * many bytes per requested character is guaranteed to decode to at least
	 * [maxChars] characters, and everything past them was going to be thrown away.
	 * Decoding a whole 5 MB file to keep 20 KB of it cost 1–2 ms and up to 10 MB
	 * of short-lived heap on a path several agents may be walking at once.
	 */
	private AttachmentContent renderText(byte[] data, int maxChars) {
		int window = (int) Math.min(data.length, (long) Math.max(maxChars, 0) * MAX_BYTES_PER_CHAR);
		if (window >= data.length) {
			String whole = new String(data, StandardCharsets.UTF_8);
			return whole.length() <= maxChars
					? new AttachmentContent.Text(whole, false)
					: new AttachmentContent.Text(whole.substring(0, maxChars), true);
		}
		// The window is cut at an arbitrary byte, so its last character may be the
		// replacement one — but it sits far past [maxChars] and is dropped here.
		String prefix = new String(data, 0, window, StandardCharsets.UTF_8);
		return new AttachmentContent.Text(prefix.substring(0, maxChars), true);
	}
}
