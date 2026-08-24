package com.ahmadre.hinata.setup;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The band that heads every transactional mail. Its job is to survive whatever
 * an operator configures — a 6:1 wordmark, a 32px favicon, a white-on-transparent
 * mark, a 120-character name, a corrupt file — without ever throwing at a caller
 * who is trying to send a password reset.
 *
 * <p>Also writes every band it renders to {@code build/mail-band/} so the design
 * can be looked at rather than only asserted:
 * <pre>./gradlew test --tests '*MailBandComposerTest' --no-build-cache &amp;&amp; open build/mail-band</pre>
 */
class MailBandComposerTest {

	private static final Path OUT = Path.of("build", "mail-band");
	private static final String ORG = "AStA der Hochschule Niederrhein";

	@Test
	void composesABandAtOneGeometryForEveryMail() throws Exception {
		byte[] band = MailBandComposer
				.composeOrganization(logo(320, 320, new Color(0xF7, 0xF6, 0xF2)), ORG, "invite")
				.orElseThrow();

		BufferedImage image = read(band);
		assertThat(image.getWidth()).isEqualTo(MailBandComposer.WIDTH);
		assertThat(image.getHeight()).isEqualTo(MailBandComposer.HEIGHT);
		// Opaque by construction: a mail client that inverts backgrounds must not
		// be able to show its own ground through the band.
		assertThat(image.getColorModel().hasAlpha()).isFalse();
		// The amber hairline closes it.
		assertThat(new Color(image.getRGB(600, MailBandComposer.HEIGHT - 3)).getRed())
				.isGreaterThan(180);
		write("org-light-logo.jpg", band);
	}

	@Test
	void everyTemplateGetsItsOwnArtworkInBothBrandingStates() throws Exception {
		// The whole point of composing rather than shipping rendered mastheads: the
		// same illustration serves an instance with a logo and one without, so
		// configuring a logo can never cost a mail its picture again.
		for (String template : new String[] {
			"default", "notification", "verify-email", "invite", "password-reset",
			"email-change-verify", "approval-request", "data-report-ready",
			"account-activated", "account-role-changed", "account-deactivated",
			"account-deleted", "issue-changes", "weekly-summary",
		}) {
			assertThat(MailBandComposer.hasBackdrop(template))
					.as("artwork for %s", template)
					.isTrue();

			byte[] hinata = MailBandComposer.composeHinata(template).orElseThrow();
			byte[] org = MailBandComposer
					.composeOrganization(logo(320, 320, new Color(0x00, 0x8B, 0xE8)), ORG, template)
					.orElseThrow();

			assertThat(read(hinata).getHeight()).isEqualTo(MailBandComposer.HEIGHT);
			assertThat(read(org).getHeight()).isEqualTo(MailBandComposer.HEIGHT);
			// Different lockups on the same artwork must not collapse to one image.
			assertThat(org).isNotEqualTo(hinata);

			write("hinata-" + template + ".jpg", hinata);
			write("org-" + template + ".jpg", org);
		}
	}

	@Test
	void anUnknownTemplateStillProducesABand() throws Exception {
		// A template added later has no artwork yet; that is a normal state, and it
		// must degrade to the neutral band rather than to a mail without a header.
		assertThat(MailBandComposer.hasBackdrop("no-such-template")).isFalse();
		byte[] band = MailBandComposer.composeHinata("no-such-template").orElseThrow();

		assertThat(read(band).getWidth()).isEqualTo(MailBandComposer.WIDTH);
		write("hinata-fallback.jpg", band);
	}

	@Test
	void givesADarkLogoALightPlateSoItDoesNotSinkIntoTheNavy() throws Exception {
		byte[] band = MailBandComposer
				.composeOrganization(logo(320, 320, new Color(0x11, 0x22, 0x44)), ORG, "default")
				.orElseThrow();

		BufferedImage image = read(band);
		// Just outside the mark, inside the plate's padding: paper, not navy.
		Color atPlate = new Color(image.getRGB(46, MailBandComposer.HEIGHT / 2));
		assertThat(atPlate.getRed()).isGreaterThan(190);
		assertThat(atPlate.getGreen()).isGreaterThan(190);
		write("org-dark-logo.jpg", band);
	}

	@Test
	void containsAWideWordmarkInsteadOfStretchingIt() throws Exception {
		// 8:1 — the shape that breaks every naive square logo slot.
		byte[] band = MailBandComposer
				.composeOrganization(logo(1600, 200, Color.WHITE), ORG, "default")
				.orElseThrow();

		BufferedImage image = read(band);
		assertThat(image.getWidth()).isEqualTo(MailBandComposer.WIDTH);
		// The mark is bounded well short of the band's right edge, so the artwork
		// behind it survives.
		assertThat(new Color(image.getRGB(MailBandComposer.WIDTH - 40,
				MailBandComposer.HEIGHT / 2)).getRed()).isLessThan(200);
		write("org-wide-wordmark.jpg", band);
	}

	@Test
	void doesNotBlowUpATinyFavicon() throws Exception {
		byte[] band = MailBandComposer
				.composeOrganization(logo(32, 32, Color.WHITE), ORG, "default")
				.orElseThrow();

		BufferedImage image = read(band);
		// 32px at most 2x = 64px tall, so the row a full-height mark would occupy
		// is still dark near the top of the band.
		assertThat(new Color(image.getRGB(70, 18)).getRed()).isLessThan(110);
		write("org-tiny-favicon.jpg", band);
	}

	@Test
	void survivesAnAbsurdOrganizationName() throws Exception {
		// Nothing stops an operator from naming their instance a sentence. The band
		// must still be a band.
		byte[] band = MailBandComposer
				.composeOrganization(logo(320, 320, Color.WHITE), "X".repeat(200), "default")
				.orElseThrow();

		assertThat(read(band).getWidth()).isEqualTo(MailBandComposer.WIDTH);
		write("org-absurd-name.jpg", band);
	}

	@Test
	void rendersWithoutANameAtAll() throws Exception {
		// An instance that never completed setup has no organization name; the mark
		// alone still identifies it.
		assertThat(MailBandComposer.composeOrganization(logo(320, 320, Color.WHITE), null, "default"))
				.isPresent();
		assertThat(MailBandComposer.composeOrganization(logo(320, 320, Color.WHITE), "  ", "default"))
				.isPresent();
	}

	@Test
	void returnsEmptyRatherThanThrowingOnUnusableInput() {
		assertThat(MailBandComposer.composeOrganization(new byte[0], ORG, "default")).isEmpty();
		assertThat(MailBandComposer.composeOrganization("not an image".getBytes(), ORG, "default"))
				.isEmpty();
		assertThat(MailBandComposer.composeOrganization(null, ORG, "default")).isEmpty();
		// A 4x4 source is not a logo anybody wants at the head of their mail.
		assertThat(MailBandComposer.composeOrganization(logo(4, 4, Color.WHITE), ORG, "default"))
				.isEmpty();
	}

	// --- helpers --------------------------------------------------------------

	private static BufferedImage read(byte[] jpeg) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(jpeg));
	}

	/** A [w]x[h] PNG: [ink] mark on a transparent ground, like a real logo. */
	private static byte[] logo(int w, int h, Color ink) {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(ink);
		g.fillRoundRect(w / 8, h / 8, w - w / 4, h - h / 4, w / 6, h / 6);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Best-effort: the gallery is a convenience, never a reason to fail a test. */
	private static void write(String name, byte[] image) {
		try {
			Files.createDirectories(OUT);
			Files.write(OUT.resolve(name), image);
		}
		catch (Exception ignored) {
			// A read-only build directory is not a defect in the composer.
		}
	}
}
