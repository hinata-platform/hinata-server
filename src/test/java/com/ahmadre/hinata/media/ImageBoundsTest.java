package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard exists for one input: a file that is small on disk and enormous once
 * decoded. Every test here is built from a hand-assembled PNG header rather than a
 * real image, because the whole point is that the dimensions are read <em>without</em>
 * the pixels — a test that allocated the bomb to prove the bomb is refused would be
 * the very out-of-memory it is testing for.
 */
class ImageBoundsTest {

	private static final String KEY = "error.storage.imageTooLarge";

	// --- the attack -----------------------------------------------------------

	@Test
	void refusesAHeaderDeclaringMorePixelsThanWeWillDecode() {
		byte[] bomb = pngHeader(50_000, 50_000);

		// 2.5 billion pixels — about 10 GB of int[] — from 45 bytes on the wire.
		assertThat(bomb.length).isLessThan(100);
		assertThatThrownBy(() -> ImageBounds.requireWithinBudget(bomb, KEY))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("imageTooLarge");
	}

	/**
	 * The pixel budget alone would admit this: 50 million pixels is exactly at the
	 * limit, but a 1×50000000 strip is not a picture and does reach the
	 * pathological paths in a scaler. That is what the edge bound is for.
	 */
	@Test
	void refusesAStripThatFitsThePixelBudgetButNotTheEdgeBound() {
		assertThatThrownBy(() -> ImageBounds.requireWithinBudget(pngHeader(1, 50_000_000), KEY))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> ImageBounds.requireWithinBudget(pngHeader(50_000_000, 1), KEY))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * The bypass a MIME-keyed implementation builds for itself.
	 *
	 * <p>{@code image/jpg} is accepted by both the avatar and the logo endpoint and
	 * is <em>not</em> a registered MIME type, so
	 * {@code getImageReadersByMIMEType("image/jpg")} finds nothing. A guard that
	 * asked the declared type would skip the check entirely — while
	 * {@code ImageIO.read} decoded the bytes anyway, because it sniffs the stream.
	 * These bytes are a PNG bomb no matter what the upload claimed they were.
	 */
	@Test
	void refusesTheBombRegardlessOfWhatTheUploadClaimedTheTypeWas() {
		assertThatThrownBy(() -> ImageBounds.requireWithinBudget(pngHeader(50_000, 50_000), KEY))
				.isInstanceOf(ApiException.class);
	}

	// --- what must still get through --------------------------------------------

	@Test
	void allowsAnOrdinaryPhotoSizedImage() {
		assertThatCode(() -> ImageBounds.requireWithinBudget(pngHeader(4000, 3000), KEY))
				.doesNotThrowAnyException();
	}

	@Test
	void allowsARealSmallImage() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", out);

		assertThatCode(() -> ImageBounds.requireWithinBudget(out.toByteArray(), KEY))
				.doesNotThrowAnyException();
		assertThat(ImageBounds.peek(out.toByteArray())).hasValueSatisfying(size -> {
			assertThat(size.width).isEqualTo(120);
			assertThat(size.height).isEqualTo(80);
		});
	}

	/**
	 * The regression this guard could most easily have caused. {@code image/webp} is
	 * on the attachment allow-list and has no {@code ImageReader} in this JDK, so a
	 * rule of "no reader means refuse" would have broken every WebP attachment in
	 * the product — while protecting nothing, because nothing in the server decodes
	 * them. Silence is the correct answer, not a refusal.
	 */
	@Test
	void staysSilentOnAFormatNoReaderRecognises() {
		byte[] webp = webpHeader();

		assertThat(ImageBounds.peek(webp)).isEmpty();
		assertThatCode(() -> ImageBounds.requireWithinBudget(webp, KEY))
				.doesNotThrowAnyException();
	}

	@Test
	void staysSilentOnBytesThatAreNotAnImageAtAll() {
		assertThatCode(() -> ImageBounds.requireWithinBudget("not an image".getBytes(), KEY))
				.doesNotThrowAnyException();
	}

	/**
	 * A PNG signature with a truncated header: the shape of a file crafted to make a
	 * reader work hard before giving up. It must cost nothing and refuse nothing —
	 * whoever decodes it next will fail on its own terms.
	 */
	@Test
	void staysSilentOnATruncatedHeader() {
		byte[] truncated = new byte[] {
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00 };

		assertThatCode(() -> ImageBounds.requireWithinBudget(truncated, KEY))
				.doesNotThrowAnyException();
	}

	@Test
	void toleratesNullAndEmpty() {
		assertThat(ImageBounds.peek(null)).isEmpty();
		assertThat(ImageBounds.peek(new byte[0])).isEmpty();
		assertThatCode(() -> ImageBounds.requireWithinBudget(null, KEY)).doesNotThrowAnyException();
	}

	// --- helpers -----------------------------------------------------------------

	/**
	 * A PNG consisting of nothing but a valid signature and IHDR chunk.
	 *
	 * <p>{@code ImageReader.getWidth(0)} parses the header and stops, so this is all
	 * the guard ever sees of any file — which is exactly why a 50000×50000 "image"
	 * can be asserted on in a unit test at all.
	 */
	private static byte[] pngHeader(int width, int height) {
		byte[] ihdr = ByteBuffer.allocate(17)
				.put("IHDR".getBytes())
				.putInt(width)
				.putInt(height)
				.put((byte) 8)   // bit depth
				.put((byte) 0)   // colour type: greyscale
				.put((byte) 0)   // compression
				.put((byte) 0)   // filter
				.put((byte) 0)   // interlace
				.array();
		CRC32 crc = new CRC32();
		crc.update(ihdr);

		return ByteBuffer.allocate(8 + 4 + ihdr.length + 4)
				.put(new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A })
				.putInt(13)
				.put(ihdr)
				.putInt((int) crc.getValue())
				.array();
	}

	/** RIFF/WEBP container header — enough for a sniffer to identify, if one existed. */
	private static byte[] webpHeader() {
		return ByteBuffer.allocate(16)
				.put("RIFF".getBytes())
				.putInt(0)
				.put("WEBPVP8 ".getBytes())
				.array();
	}
}
