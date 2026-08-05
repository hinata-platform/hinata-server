package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Optional;

/**
 * Refuses an image whose header declares more pixels than we are willing to
 * decode, before anything decodes it.
 *
 * <h2>The attack</h2>
 *
 * <p>Every upload path bounds the <em>encoded</em> size, and that bound says almost
 * nothing about the decoded one. A flat-colour 50000×50000 PNG compresses to a few
 * hundred kilobytes and expands to roughly ten gigabytes of {@code int[]} the
 * instant {@code ImageIO.read} touches it — an out-of-memory kill of the whole JVM
 * from one upload that passed every size check in the product. {@code ImageIO} has
 * no pixel budget of its own; this is the only place one can exist.
 *
 * <h2>Why the type is sniffed rather than trusted</h2>
 *
 * <p>The obvious implementation asks {@code ImageIO.getImageReadersByMIMEType} for
 * the declared content type. It builds its own bypass: {@code image/jpg} — which
 * both the avatar and the logo endpoints accept — is not a registered MIME type and
 * resolves to no reader at all, so a budget keyed on it would quietly skip the
 * check, while {@code ImageIO.read} goes on to decode the bytes anyway because it
 * sniffs the stream. The guard has to make its decision from the same evidence the
 * decoder will: the bytes.
 *
 * <h2>Why an unreadable image is not refused here</h2>
 *
 * <p>When no reader recognises the stream, this class says nothing. It is tempting
 * to treat that as hostile, but it is the wrong call in both directions: the
 * callers that decode already answer it — {@code ImageIO.read} returns null and
 * they raise their own {@code unreadable} message — and the callers that merely
 * store bytes have no business rejecting a format they never open.
 * {@code image/webp} is exactly that case: it is on the attachment allow-list and
 * has no reader in this JDK, so "no reader means reject" would have broken every
 * WebP attachment in the product while protecting nothing, since nothing decodes
 * them here. What does decode them is the image classifier sidecar, which carries
 * this same budget on its own side of the wire.
 */
@Slf4j
public final class ImageBounds {

	/**
	 * Largest decoded pixel count accepted, ~50 MP.
	 *
	 * <p>Sized against real uploads rather than against the attack: a 100-megapixel
	 * camera is not a thing anyone attaches to a bug report, while the smallest
	 * useful bomb is orders of magnitude past this. At four bytes per pixel this
	 * still permits a 200 MB decode, which is survivable; the point is to exclude
	 * the ten-gigabyte one.
	 */
	public static final long MAX_PIXELS = 50_000_000L;

	/**
	 * Longest edge accepted. A separate bound because the pixel budget alone lets
	 * through a 1×50000000 strip, which is not a picture and does reach the
	 * pathological paths in a scaler.
	 */
	public static final int MAX_EDGE_PX = 20_000;

	private ImageBounds() {
	}

	/**
	 * Refuses [data] when its declared dimensions exceed the budget.
	 *
	 * <p>Reads the header only — {@link ImageReader#getWidth} does not decode
	 * pixels — so the cost is a few bytes regardless of what was uploaded.
	 *
	 * @param tooLargeKey i18n key raised on refusal, so each surface keeps its own
	 *                    wording ({@code error.avatar.*} vs {@code error.storage.*})
	 * @throws ApiException 400 when the image is larger than we will decode
	 */
	public static void requireWithinBudget(byte[] data, String tooLargeKey) {
		peek(data).ifPresent(size -> {
			long pixels = (long) size.width * size.height;
			if (pixels > MAX_PIXELS || size.width > MAX_EDGE_PX || size.height > MAX_EDGE_PX) {
				log.warn("Refused an image declaring {}x{} ({} pixels)", size.width, size.height, pixels);
				throw ApiException.badRequest(tooLargeKey);
			}
		});
	}

	/**
	 * The dimensions [data] declares, or empty when nothing can read it.
	 *
	 * <p>Empty means "no opinion", never "safe" — see the class note.
	 */
	public static Optional<Dimension> peek(byte[] data) {
		if (data == null || data.length == 0) {
			return Optional.empty();
		}
		try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			if (stream == null) {
				return Optional.empty();
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				return Optional.empty();
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream);
				return Optional.of(new Dimension(reader.getWidth(0), reader.getHeight(0)));
			}
			finally {
				reader.dispose();
			}
		}
		catch (Exception ex) {
			// A header too broken to state its own size is not this class's problem:
			// whoever decodes it next will fail, and whoever only stores it does not care.
			log.debug("Could not read image dimensions: {}", ex.toString());
			return Optional.empty();
		}
	}
}
