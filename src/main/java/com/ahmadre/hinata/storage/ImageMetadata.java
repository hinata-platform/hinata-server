package com.ahmadre.hinata.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Takes the metadata out of an embedded image before it is stored (HIN-177): EXIF with the place a
 * photo was taken, XMP, IPTC, comments and whatever a vendor tucks into its own blocks. By cutting
 * the blocks out rather than decoding and encoding the image again, so quality and animation stay
 * and a WebP the JVM cannot decode needs no detour. What stays is a short list of blocks an image
 * needs to be drawn right; everything else goes, so an unknown block never carries anything along.
 * Only a JPEG that its EXIF turns is drawn upright and encoded anew, or it would lie on its side
 * once the EXIF is gone. Anything after the image's end goes too.
 *
 * <p>Anything whose blocks do not read cleanly is refused, never stored as it came.
 */
public final class ImageMetadata {

	/** The quality a JPEG drawn upright is encoded with. */
	static final float UPRIGHT_QUALITY = 0.9f;

	private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] ICC = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
	/** The ancillary PNG chunks that change how the image looks: colour, transparency, animation. */
	private static final Set<String> PNG_KEPT = Set.of("tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD",
			"pHYs", "cICP", "mDCV", "cLLI", "acTL", "fcTL", "fdAT");
	private static final Set<String> WEBP_KEPT = Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP");
	/** The GIF application blocks that change how it plays or looks: the loop and the colour profile. */
	private static final Set<String> GIF_KEPT = Set.of("NETSCAPE2.0", "ANIMEXTS1.0", "ICCRGBG1012");
	private static final int WEBP_EXIF_AND_XMP = 0x08 | 0x04;

	private ImageMetadata() {
	}

	/** The image's blocks could not be read safely. */
	public static final class UnreadableException extends Exception {

		UnreadableException() {
			super("image blocks unreadable");
		}
	}

	/** [data] of [contentType] without its metadata; other types come back as they are. */
	public static byte[] strip(byte[] data, String contentType) throws UnreadableException {
		try {
			return switch (contentType) {
				case "image/jpeg" -> jpeg(data);
				case "image/png" -> png(data);
				case "image/webp" -> webp(data);
				case "image/gif" -> gif(data);
				default -> data;
			};
		}
		catch (IndexOutOfBoundsException truncated) {
			throw new UnreadableException();
		}
	}

	// --- JPEG ------------------------------------------------------------------------------------

	/**
	 * With only JFIF, the colour profile and Adobe's colour transform of the application blocks, no
	 * comment, and upright when the EXIF said it was turned. EXIF and XMP sit in APP1, IPTC in APP13.
	 */
	static byte[] jpeg(byte[] in) throws UnreadableException {
		if (in.length < 4 || u8(in, 0) != 0xFF || u8(in, 1) != 0xD8) {
			throw new UnreadableException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, 2);
		int orientation = 1;
		int i = 2;
		boolean ended = false;
		while (i < in.length) {
			if (u8(in, i) != 0xFF) {
				throw new UnreadableException();
			}
			while (u8(in, i + 1) == 0xFF) {
				i++;
			}
			int marker = u8(in, i + 1);
			if (marker == 0xD9) {
				out.write(in, i, 2);
				ended = true;
				break;
			}
			if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) {
				out.write(in, i, 2);
				i += 2;
				continue;
			}
			int length = u16(in, i + 2);
			int end = i + 2 + length;
			if (length < 2 || end > in.length) {
				throw new UnreadableException();
			}
			if (marker == 0xE1 && startsWith(in, i + 4, EXIF)) {
				orientation = orientationOf(in, i + 4 + EXIF.length, end);
			}
			if (keepsJpegSegment(in, i, marker)) {
				out.write(in, i, end - i);
			}
			i = end;
			if (marker == 0xDA) {
				// Entropy-coded data up to the next marker; stuffed bytes and restarts belong to it.
				int next = scanEntropy(in, i);
				out.write(in, i, next - i);
				i = next;
			}
		}
		if (!ended) {
			throw new UnreadableException();
		}
		byte[] stripped = out.toByteArray();
		return orientation >= 2 && orientation <= 8 ? upright(stripped, orientation) : stripped;
	}

	private static boolean keepsJpegSegment(byte[] in, int at, int marker) {
		if (marker == 0xE0 || marker == 0xEE) {
			return true;
		}
		if (marker == 0xE2) {
			return startsWith(in, at + 4, ICC);
		}
		return !(marker >= 0xE0 && marker <= 0xEF) && marker != 0xFE;
	}

	private static int scanEntropy(byte[] in, int from) {
		int j = from;
		while (j < in.length - 1) {
			if (u8(in, j) == 0xFF) {
				int next = u8(in, j + 1);
				if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) {
					return j;
				}
			}
			j++;
		}
		return in.length;
	}

	/** The EXIF orientation in the TIFF block from [start] to [end]; 1 when it says none or reads badly. */
	private static int orientationOf(byte[] in, int start, int end) {
		if (start + 8 > end) {
			return 1;
		}
		boolean little = in[start] == 'I' && in[start + 1] == 'I';
		boolean big = in[start] == 'M' && in[start + 1] == 'M';
		if (!little && !big) {
			return 1;
		}
		long ifd = start + read32(in, start + 4, little);
		if (ifd + 2 > end) {
			return 1;
		}
		int entries = read16(in, (int) ifd, little);
		for (int n = 0; n < entries; n++) {
			int entry = (int) ifd + 2 + n * 12;
			if (entry + 12 > end) {
				return 1;
			}
			if (read16(in, entry, little) == 0x0112) {
				int value = read16(in, entry + 8, little);
				return value >= 1 && value <= 8 ? value : 1;
			}
		}
		return 1;
	}

	/**
	 * [jpeg] drawn the way its orientation said. A JPEG the JVM cannot decode, such as one in CMYK,
	 * stays as it is: lying on its side is better than not being stored, and its metadata is gone
	 * either way.
	 */
	private static byte[] upright(byte[] jpeg, int orientation) {
		BufferedImage image = ImageOps.read(jpeg);
		if (image == null) {
			return jpeg;
		}
		try {
			return ImageOps.encodeJpeg(ImageOps.orient(image, orientation), UPRIGHT_QUALITY);
		}
		catch (IOException ex) {
			return jpeg;
		}
	}

	// --- PNG -------------------------------------------------------------------------------------

	/**
	 * With the critical chunks and those that change how the image looks; text ({@code tEXt},
	 * {@code iTXt}, {@code zTXt}), EXIF ({@code eXIf}), the time and private chunks go.
	 */
	static byte[] png(byte[] in) throws UnreadableException {
		if (!startsWith(in, 0, PNG_SIGNATURE)) {
			throw new UnreadableException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, PNG_SIGNATURE.length);
		int i = PNG_SIGNATURE.length;
		while (i < in.length) {
			if (i + 12 > in.length) {
				throw new UnreadableException();
			}
			long length = read32(in, i, false);
			if (length > in.length - i - 12L) {
				throw new UnreadableException();
			}
			String type = new String(in, i + 4, 4, StandardCharsets.ISO_8859_1);
			int end = i + 12 + (int) length;
			if (Character.isUpperCase(type.charAt(0)) || PNG_KEPT.contains(type)) {
				out.write(in, i, end - i);
			}
			i = end;
			if ("IEND".equals(type)) {
				return out.toByteArray();
			}
		}
		throw new UnreadableException();
	}

	// --- WebP ------------------------------------------------------------------------------------

	/**
	 * With the chunks that draw the image, its alpha, its animation and its colour profile; without
	 * {@code EXIF}, {@code XMP } and unknown chunks, and with the flags for the first two cleared in
	 * {@code VP8X}. Every chunk kept is padded to an even length, as RIFF wants.
	 */
	static byte[] webp(byte[] in) throws UnreadableException {
		if (in.length < 12 || !startsWith(in, 0, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
				|| !startsWith(in, 8, "WEBP".getBytes(StandardCharsets.ISO_8859_1))) {
			throw new UnreadableException();
		}
		long riff = read32(in, 4, true);
		if (riff < 4 || riff + 8 > in.length) {
			throw new UnreadableException();
		}
		int limit = (int) (riff + 8);
		ByteArrayOutputStream chunks = new ByteArrayOutputStream(in.length);
		chunks.write(in, 8, 4);
		int flagsAt = -1;
		int i = 12;
		while (i < limit) {
			if (i + 8 > limit) {
				throw new UnreadableException();
			}
			String fourcc = new String(in, i, 4, StandardCharsets.ISO_8859_1);
			long size = read32(in, i + 4, true);
			if (i + 8 + size > limit) {
				throw new UnreadableException();
			}
			if (WEBP_KEPT.contains(fourcc)) {
				if ("VP8X".equals(fourcc)) {
					if (size < 10) {
						throw new UnreadableException();
					}
					flagsAt = chunks.size() + 8;
				}
				chunks.write(in, i, (int) (8 + size));
				if ((size & 1) == 1) {
					chunks.write(0);
				}
			}
			// Some writers leave out the pad byte of the last chunk.
			i = (int) Math.min(limit, i + 8 + size + (size & 1));
		}
		byte[] body = chunks.toByteArray();
		if (flagsAt >= 0) {
			body[flagsAt] = (byte) (body[flagsAt] & ~WEBP_EXIF_AND_XMP);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 8);
		out.write('R');
		out.write('I');
		out.write('F');
		out.write('F');
		writeLittle32(out, body.length);
		out.write(body, 0, body.length);
		return out.toByteArray();
	}

	// --- GIF -------------------------------------------------------------------------------------

	/**
	 * With every frame, its timing and the loop; without comments and without application blocks
	 * other than the loop and the colour profile, which is where XMP and IPTC go.
	 */
	static byte[] gif(byte[] in) throws UnreadableException {
		if (in.length < 13 || in[0] != 'G' || in[1] != 'I' || in[2] != 'F') {
			throw new UnreadableException();
		}
		int packed = u8(in, 10);
		int i = 13 + ((packed & 0x80) != 0 ? 3 * (1 << ((packed & 0x07) + 1)) : 0);
		if (i > in.length) {
			throw new UnreadableException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, i);
		while (i < in.length) {
			int block = u8(in, i);
			if (block == 0x3B) {
				out.write(0x3B);
				return out.toByteArray();
			}
			int start = i;
			if (block == 0x2C) {
				int local = u8(in, i + 9);
				i += 10 + ((local & 0x80) != 0 ? 3 * (1 << ((local & 0x07) + 1)) : 0);
				i = subBlocks(in, i + 1);
				out.write(in, start, i - start);
			}
			else if (block == 0x21) {
				int label = u8(in, i + 1);
				boolean kept = switch (label) {
					case 0xFE -> false;
					case 0xFF -> u8(in, i + 2) == 11
							&& GIF_KEPT.contains(new String(in, i + 3, 11, StandardCharsets.ISO_8859_1));
					default -> true;
				};
				i = subBlocks(in, i + 2);
				if (kept) {
					out.write(in, start, i - start);
				}
			}
			else {
				throw new UnreadableException();
			}
		}
		throw new UnreadableException();
	}

	/** Past the data sub-blocks from [from] and their terminator. */
	private static int subBlocks(byte[] in, int from) throws UnreadableException {
		int j = from;
		while (true) {
			int size = u8(in, j);
			j++;
			if (size == 0) {
				return j;
			}
			j += size;
			if (j > in.length) {
				throw new UnreadableException();
			}
		}
	}

	// --- bytes -----------------------------------------------------------------------------------

	private static int u8(byte[] in, int at) {
		return in[at] & 0xFF;
	}

	private static int u16(byte[] in, int at) {
		return (u8(in, at) << 8) | u8(in, at + 1);
	}

	private static int read16(byte[] in, int at, boolean little) {
		return little ? u8(in, at) | (u8(in, at + 1) << 8) : u16(in, at);
	}

	private static long read32(byte[] in, int at, boolean little) {
		long a = u8(in, at);
		long b = u8(in, at + 1);
		long c = u8(in, at + 2);
		long d = u8(in, at + 3);
		return little ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
	}

	private static void writeLittle32(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
		out.write((value >>> 16) & 0xFF);
		out.write((value >>> 24) & 0xFF);
	}

	private static boolean startsWith(byte[] in, int at, byte[] prefix) {
		if (at < 0 || at + prefix.length > in.length) {
			return false;
		}
		for (int k = 0; k < prefix.length; k++) {
			if (in[at + k] != prefix[k]) {
				return false;
			}
		}
		return true;
	}
}
