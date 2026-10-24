package com.ahmadre.hinata.storage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Files written byte by byte for the upload gate's tests (HIN-177): images that carry metadata
 * where cameras and editors put it, a picture that declares more pixels than it has, and the first
 * bytes of the other kinds. Written by hand because ImageIO attaches no EXIF, and a fixture that
 * only claims to carry a GPS position would prove nothing.
 */
public final class TestImages {

	private TestImages() {
	}

	/** {@code Exif\0\0}, how every EXIF APP1 payload starts. */
	public static final byte[] EXIF_SIGNATURE = { 'E', 'x', 'i', 'f', 0, 0 };

	/** The GPS IFD of {@link #jpegWithExif}, also the needle when asserting it is gone. */
	public static byte[] gpsIfd() {
		ByteArrayOutputStream ifd = new ByteArrayOutputStream();
		writeShort(ifd, 1);                            // one entry
		writeShort(ifd, 0x0001);                       // tag: GPSLatitudeRef
		writeShort(ifd, 2);                            // type: ASCII
		writeInt(ifd, 2);                              // count: "N\0"
		write(ifd, new byte[] { 'N', 0, 0, 0 });       // fits inline
		writeInt(ifd, 0);                              // no further IFD
		return ifd.toByteArray();
	}

	/** A PNG of one colour. */
	public static byte[] png(int width, int height) {
		return encode(solid(width, height, new Color(180, 60, 40)), "png");
	}

	/** A JPEG whose left half is red and right half blue. */
	public static byte[] jpeg(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, width / 2, height);
		g.setColor(Color.BLUE);
		g.fillRect(width / 2, 0, width - width / 2, height);
		g.dispose();
		return encode(image, "jpeg");
	}

	/**
	 * {@link #jpeg} with an APP1 EXIF segment behind the JFIF one, as a camera writes it: IFD0
	 * holds [orientation] and a pointer to a GPS IFD with {@code GPSLatitudeRef = "N"}, and an XMP
	 * APP1, an IPTC APP13 and a comment follow.
	 */
	public static byte[] jpegWithExif(int width, int height, int orientation) {
		byte[] plain = jpeg(width, height);
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		write(tiff, new byte[] { 'M', 'M' });          // big endian
		writeShort(tiff, 42);                          // TIFF magic
		writeInt(tiff, 8);                             // IFD0 starts right after
		writeShort(tiff, 2);                           // IFD0: two entries
		writeShort(tiff, 0x0112);                      // tag: Orientation
		writeShort(tiff, 3);                           // type: SHORT
		writeInt(tiff, 1);
		writeShort(tiff, orientation);
		writeShort(tiff, 0);
		writeShort(tiff, 0x8825);                      // tag: GPS IFD pointer
		writeShort(tiff, 4);                           // type: LONG
		writeInt(tiff, 1);
		writeInt(tiff, 38);                            // 8 + 2 + 2 * 12 + 4
		writeInt(tiff, 0);                             // no further IFD
		write(tiff, gpsIfd());

		ByteArrayOutputStream segments = new ByteArrayOutputStream();
		segment(segments, 0xE1, concat(EXIF_SIGNATURE, tiff.toByteArray()));
		segment(segments, 0xE1, ("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>Hamburg</x:xmpmeta>")
				.getBytes(StandardCharsets.ISO_8859_1));
		segment(segments, 0xED, "Photoshop 3.0\08BIM city=Hamburg".getBytes(StandardCharsets.ISO_8859_1));
		segment(segments, 0xFE, "taken at home".getBytes(StandardCharsets.ISO_8859_1));

		// Behind the writer's JFIF APP0, where a camera puts it.
		int at = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(plain, 0, at);
		write(out, segments.toByteArray());
		out.write(plain, at, plain.length - at);
		return out.toByteArray();
	}

	/** A PNG carrying text, EXIF, a time and a private chunk behind its header. */
	public static byte[] pngWithText(int width, int height) {
		byte[] plain = png(width, height);
		int afterHeader = 8 + 12 + 13;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(plain, 0, afterHeader);
		write(out, chunk("tEXt", "Comment\0taken at home".getBytes(StandardCharsets.ISO_8859_1)));
		write(out, chunk("iTXt", "Location\0\0\0\0\0Hamburg".getBytes(StandardCharsets.ISO_8859_1)));
		write(out, chunk("zTXt", "Author\0\0x".getBytes(StandardCharsets.ISO_8859_1)));
		write(out, chunk("eXIf", concat(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0 }, new byte[4])));
		write(out, chunk("tIME", new byte[] { 0x07, (byte) 0xEA, 10, 24, 12, 0, 0 }));
		write(out, chunk("prVt", "private".getBytes(StandardCharsets.ISO_8859_1)));
		out.write(plain, afterHeader, plain.length - afterHeader);
		return out.toByteArray();
	}

	/** A PNG that declares [width] × [height] pixels and holds none. */
	public static byte[] pngDeclaring(int width, int height) {
		ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
		writeInt(ihdr, width);
		writeInt(ihdr, height);
		write(ihdr, new byte[] { 8, 2, 0, 0, 0 });
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
		write(out, chunk("IHDR", ihdr.toByteArray()));
		write(out, chunk("IDAT", new byte[] { 0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01 }));
		write(out, chunk("IEND", new byte[0]));
		return out.toByteArray();
	}

	/**
	 * A GIF of [frames] frames of one pixel each that loops, and carries a comment and an XMP
	 * packet with its magic trailer, as Photoshop writes one.
	 */
	public static byte[] animatedGif(int frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, "GIF89a".getBytes(StandardCharsets.US_ASCII));
		write(out, new byte[] { 1, 0, 1, 0, (byte) 0x80, 0, 0 });
		write(out, new byte[] { 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
		write(out, new byte[] { 0x21, (byte) 0xFF, 11 });
		write(out, "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
		write(out, new byte[] { 3, 1, 0, 0, 0 });
		write(out, new byte[] { 0x21, (byte) 0xFE, 13 });
		write(out, "taken at home".getBytes(StandardCharsets.US_ASCII));
		out.write(0);
		write(out, new byte[] { 0x21, (byte) 0xFF, 11 });
		write(out, "XMP DataXMP".getBytes(StandardCharsets.US_ASCII));
		write(out, "<x:xmpmeta>Hamburg</x:xmpmeta>".getBytes(StandardCharsets.US_ASCII));
		out.write(1);
		for (int value = 0xFF; value >= 0; value--) {
			out.write(value);
		}
		out.write(0);
		for (int frame = 0; frame < frames; frame++) {
			write(out, new byte[] { 0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0 });
			write(out, new byte[] { 0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0 });
			write(out, new byte[] { 2, 2, 0x44, 0x01, 0 });
		}
		out.write(0x3B);
		return out.toByteArray();
	}

	/** A WebP with EXIF and XMP flagged in its VP8X header, an odd-sized EXIF chunk and an unknown one. */
	public static byte[] webpWithMetadata() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		write(body, "WEBP".getBytes(StandardCharsets.US_ASCII));
		write(body, riffChunk("VP8X", new byte[] { 0x0C | 0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0 }));
		write(body, riffChunk("ICCP", "icc".getBytes(StandardCharsets.US_ASCII)));
		write(body, riffChunk("VP8L", new byte[] { 0x2F, 0, 0, 0, 0 }));
		write(body, riffChunk("EXIF", concat(new byte[] { 'M', 'M', 0, 42 }, "GPS".getBytes(StandardCharsets.US_ASCII))));
		write(body, riffChunk("XMP ", "<x:xmpmeta/>".getBytes(StandardCharsets.US_ASCII)));
		write(body, riffChunk("ABCD", "who knows".getBytes(StandardCharsets.US_ASCII)));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, "RIFF".getBytes(StandardCharsets.US_ASCII));
		writeLittleInt(out, body.size());
		write(out, body.toByteArray());
		return out.toByteArray();
	}

	/** The first bytes of an M4A recording, as iOS writes one. */
	public static byte[] m4a() {
		return concat(new byte[] { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0 },
				new byte[64]);
	}

	/** The first bytes of a WebM recording, as a browser writes one. */
	public static byte[] webm() {
		return concat(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86 },
				new byte[64]);
	}

	/** A PDF, as far as its first bytes go. */
	public static byte[] pdf() {
		return "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
	}

	/** A Windows program: {@code MZ}, then the DOS header. */
	public static byte[] exe() {
		return concat(new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0 },
				new byte[64]);
	}

	/** The local file header a ZIP, and so every OOXML document, starts with. */
	public static byte[] zip() {
		return concat(new byte[] { 'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0 }, new byte[64]);
	}

	/** First index of [needle] in [haystack], or -1. */
	public static int indexOf(byte[] haystack, byte[] needle) {
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

	public static int indexOf(byte[] haystack, String needle) {
		return indexOf(haystack, needle.getBytes(StandardCharsets.ISO_8859_1));
	}

	// --- writing -------------------------------------------------------------

	private static BufferedImage solid(int width, int height, Color color) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, format, out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
		out.write(0xFF);
		out.write(marker);
		// The length counts its own two bytes.
		writeShort(out, payload.length + 2);
		write(out, payload);
	}

	private static byte[] chunk(String type, byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeInt(out, data.length);
		byte[] typed = concat(type.getBytes(StandardCharsets.US_ASCII), data);
		write(out, typed);
		CRC32 crc = new CRC32();
		crc.update(typed);
		writeInt(out, (int) crc.getValue());
		return out.toByteArray();
	}

	private static byte[] riffChunk(String fourcc, byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, fourcc.getBytes(StandardCharsets.US_ASCII));
		writeLittleInt(out, data.length);
		write(out, data);
		if (data.length % 2 == 1) {
			out.write(0);
		}
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

	private static void writeLittleInt(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}

	private static void write(ByteArrayOutputStream out, byte[] bytes) {
		out.write(bytes, 0, bytes.length);
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] joined = new byte[first.length + second.length];
		System.arraycopy(first, 0, joined, 0, first.length);
		System.arraycopy(second, 0, joined, first.length, second.length);
		return joined;
	}
}
