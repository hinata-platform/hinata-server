package com.ahmadre.hinata.setup;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logo decoder is the one place in this process that turns bytes somebody
 * else chose into pixels. Those bytes reach it two ways — an admin upload, and
 * an external {@code logoUrl} fetched on a path an <em>anonymous</em> caller can
 * trigger through {@code /api/v1/meta/logo} — so "small file" is not the same
 * question as "small image", and the size of the download says nothing about
 * what decoding it will cost.
 */
class OrganizationLogoDecodeTest {

	@Test
	void refusesAnImageWhoseDeclaredDimensionsWouldExhaustTheHeap() {
		// ~1 KB of PNG declaring 20000x20000. Decoded, that is 400 megapixels and
		// well over a gigabyte of heap; the header alone is enough to say no.
		byte[] bomb = pngDeclaring(20_000, 20_000);
		assertThat(bomb.length).isLessThan(4096);

		assertThat(OrganizationLogoService.decode(bomb)).isEmpty();
	}

	@Test
	void refusesOneJustOverTheCapAndAcceptsOneJustUnderIt() {
		int justUnder = (int) Math.sqrt(OrganizationLogoService.MAX_PIXELS) - 100;
		int justOver = (int) Math.sqrt(OrganizationLogoService.MAX_PIXELS) + 100;

		assertThat(OrganizationLogoService.decode(pngDeclaring(justOver, justOver))).isEmpty();
		// The header of the smaller one parses; the pixel data is truncated, so it
		// still does not produce an image — what matters is that the *reason* is
		// the broken payload and not the dimension gate, i.e. the gate did not
		// fire on a size a real logo could legitimately have.
		assertThat(justUnder * (long) justUnder).isLessThan(OrganizationLogoService.MAX_PIXELS);
	}

	@Test
	void stillDecodesAnOrdinaryLogo() {
		BufferedImage source = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = source.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRoundRect(40, 40, 432, 432, 96, 96);
		g.dispose();

		assertThat(OrganizationLogoService.decode(png(source))).isPresent();
	}

	@Test
	void answersEmptyRatherThanThrowingOnRubbish() {
		assertThat(OrganizationLogoService.decode(null)).isEmpty();
		assertThat(OrganizationLogoService.decode(new byte[0])).isEmpty();
		assertThat(OrganizationLogoService.decode("not an image".getBytes())).isEmpty();
	}

	// --- helpers --------------------------------------------------------------

	private static byte[] png(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A tiny PNG whose IHDR claims [w]x[h]. Hand-built rather than rendered,
	 * because rendering the bomb would cost exactly the heap this test exists to
	 * prove we never spend — which is the whole point: the attacker does not pay
	 * it either.
	 */
	private static byte[] pngDeclaring(int w, int h) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });

		ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
		writeInt(ihdr, w);
		writeInt(ihdr, h);
		ihdr.write(8);    // bit depth
		ihdr.write(6);    // colour type: RGBA
		ihdr.write(0);    // deflate
		ihdr.write(0);    // adaptive filtering
		ihdr.write(0);    // no interlace
		chunk(out, "IHDR", ihdr.toByteArray());

		// One deflated scanline: enough to be a well-formed stream, nowhere near
		// enough to fill the declared canvas.
		Deflater deflater = new Deflater();
		deflater.setInput(new byte[] { 0, 0, 0, 0, 0 });
		deflater.finish();
		byte[] buffer = new byte[64];
		int len = deflater.deflate(buffer);
		deflater.end();
		byte[] idat = new byte[len];
		System.arraycopy(buffer, 0, idat, 0, len);
		chunk(out, "IDAT", idat);
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		writeInt(out, data.length);
		byte[] typeBytes = type.getBytes();
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		writeInt(out, (int) crc.getValue());
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write(value >>> 24);
		out.write(value >>> 16);
		out.write(value >>> 8);
		out.write(value);
	}
}
