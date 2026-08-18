package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one pipeline every avatar goes through — a person's, a team's, a
 * project's. What is pinned here therefore holds for all three at once: the
 * limits, the rejection messages, and the guarantee that a photo's metadata
 * (a camera's GPS fix above all) never leaves the server with the picture.
 */
class AvatarImagesTest {

	private static byte[] png(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(180, 60, 40));
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static MockMultipartFile upload(String contentType, byte[] bytes) {
		return new MockMultipartFile("file", "pic", contentType, bytes);
	}

	@Test
	void aTallPhotoIsCroppedSquareAndCappedAtTheMaxEdge() throws Exception {
		byte[] jpeg = AvatarImages.compress(upload("image/png", png(900, 1400)));

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertThat(decoded.getWidth()).isEqualTo(AvatarImages.MAX_SIZE);
		assertThat(decoded.getHeight()).isEqualTo(AvatarImages.MAX_SIZE);
	}

	@Test
	void aSmallSourceIsNeverBlownUp() throws Exception {
		byte[] jpeg = AvatarImages.compress(upload("image/png", png(200, 200)));

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertThat(decoded.getWidth()).isEqualTo(200);
	}

	@Test
	void anEmptyUploadIsRejected() {
		assertThatThrownBy(() -> AvatarImages.compress(upload("image/png", new byte[0])))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.empty");
	}

	@Test
	void anUploadOverTheSizeCapIsRejectedBeforeItIsDecoded() {
		// One byte past 12 MiB — the guard exists so a phone photo cannot be used
		// to make the server decode (and hold) an arbitrarily large raster.
		byte[] tooBig = new byte[(int) AvatarImages.MAX_UPLOAD_BYTES + 1];

		assertThatThrownBy(() -> AvatarImages.compress(upload("image/jpeg", tooBig)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.tooLarge");
	}

	@Test
	void aTypeTheServerCannotDecodeIsRejected() throws Exception {
		assertThatThrownBy(() -> AvatarImages.compress(upload("image/webp", png(200, 200))))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.unsupportedType");
	}

	/**
	 * The declared content type is a claim, not evidence. A payload that is not
	 * actually an image has to fail here — it is the only place that would notice
	 * before the bytes are stored and later served back to a browser.
	 */
	@Test
	void aNonImageWearingAnImageContentTypeIsRejected() {
		byte[] spoofed = "<?php echo 'not a png'; ?>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> AvatarImages.compress(upload("image/png", spoofed)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.avatar.unreadable");
	}

	/**
	 * The reason avatars are re-encoded rather than stored as uploaded: a photo
	 * straight off a phone carries EXIF, and EXIF carries where the picture was
	 * taken. Serving that back would publish a member's home address to everyone
	 * who can see their avatar.
	 */
	@Test
	void exifIncludingGpsIsStrippedFromTheStoredPicture() throws Exception {
		byte[] source = jpegWithExifGps(900, 600);
		// Guard the fixture: if this ever stops carrying EXIF, the assertions
		// below would pass without proving anything.
		assertThat(indexOf(source, EXIF_SIGNATURE)).isNotEqualTo(-1);
		assertThat(indexOf(source, APP1_MARKER)).isNotEqualTo(-1);

		byte[] stored = AvatarImages.compress(upload("image/jpeg", source));

		assertThat(indexOf(stored, EXIF_SIGNATURE)).as("Exif header").isEqualTo(-1);
		// 0xFF in entropy-coded JPEG data is always followed by 0x00 or a marker,
		// so an FF E1 pair anywhere means a surviving APP1 segment — nothing else.
		assertThat(indexOf(stored, APP1_MARKER)).as("APP1 segment").isEqualTo(-1);
		assertThat(indexOf(stored, gpsIfd())).as("GPS IFD").isEqualTo(-1);
		// Still a real, decodable picture after the strip.
		assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
	}

	// --- EXIF fixture ---------------------------------------------------------

	private static final byte[] APP1_MARKER = { (byte) 0xFF, (byte) 0xE1 };

	/** {@code Exif\0\0} — how every EXIF APP1 payload starts. */
	private static final byte[] EXIF_SIGNATURE = { 'E', 'x', 'i', 'f', 0, 0 };

	/**
	 * A JPEG with a hand-written APP1 EXIF segment holding a GPS IFD. Written by
	 * hand because ImageIO cannot attach EXIF, and a fixture that only *claims* to
	 * carry GPS would not prove the strip.
	 */
	private static byte[] jpegWithExifGps(int width, int height) throws Exception {
		byte[] plain = jpeg(width, height);
		byte[] payload = concat(EXIF_SIGNATURE, tiffWithGps());
		ByteArrayOutputStream segment = new ByteArrayOutputStream();
		segment.write(APP1_MARKER);
		// Segment length counts the two length bytes themselves.
		writeShort(segment, payload.length + 2);
		segment.write(payload);

		// Slot it in behind the writer's JFIF APP0, where a camera puts it.
		int at = endOfFirstSegment(plain);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(plain, 0, at);
		out.write(segment.toByteArray());
		out.write(plain, at, plain.length - at);
		return out.toByteArray();
	}

	/**
	 * A minimal big-endian TIFF block: IFD0 holds a single GPS-IFD pointer, and
	 * the GPS IFD holds {@code GPSLatitudeRef = "N"}.
	 */
	private static byte[] tiffWithGps() throws Exception {
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write(new byte[] { 'M', 'M' });          // big endian
		writeShort(tiff, 42);                          // TIFF magic
		writeInt(tiff, 8);                             // IFD0 starts right after
		writeShort(tiff, 1);                           // IFD0: one entry
		writeShort(tiff, 0x8825);                      // tag: GPS IFD pointer
		writeShort(tiff, 4);                           // type: LONG
		writeInt(tiff, 1);                             // count
		writeInt(tiff, 26);                            // -> offset of the GPS IFD
		writeInt(tiff, 0);                             // no further IFD
		tiff.write(gpsIfd());
		return tiff.toByteArray();
	}

	/** The GPS IFD itself, also used as a needle when asserting it is gone. */
	private static byte[] gpsIfd() throws Exception {
		ByteArrayOutputStream ifd = new ByteArrayOutputStream();
		writeShort(ifd, 1);                            // one entry
		writeShort(ifd, 0x0001);                       // tag: GPSLatitudeRef
		writeShort(ifd, 2);                            // type: ASCII
		writeInt(ifd, 2);                              // count: "N\0"
		ifd.write(new byte[] { 'N', 0, 0, 0 });        // fits inline
		writeInt(ifd, 0);                              // no further IFD
		return ifd.toByteArray();
	}

	/** Offset just past the first marker segment following SOI. */
	private static int endOfFirstSegment(byte[] jpeg) {
		int length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
		return 4 + length;
	}

	private static byte[] jpeg(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(40, 90, 160));
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", out);
		return out.toByteArray();
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static byte[] concat(byte[] first, byte[] second) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(first);
		out.write(second);
		return out.toByteArray();
	}

	/** First index of [needle] in [haystack], or -1. */
	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
