package com.ahmadre.hinata.setup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paints the band that opens every transactional mail: a backdrop illustration
 * chosen for that mail, a navy scrim across its left half, and the brand lockup
 * — the organization's logo and name where one is configured, Hinata's hex and
 * wordmark otherwise — closed by the amber hairline.
 *
 * <p>Composing it here, rather than shipping a rendered image per mail, is what
 * lets one set of artwork serve both branding states. It also means an
 * organization keeps the illustration that belongs to its mail: before this, a
 * configured logo replaced the whole masthead and every mail lost its picture.
 *
 * <p>The result is a flat, opaque image rather than an HTML row holding a small
 * {@code <img>} over a {@code bgcolor}, for one concrete reason: Gmail's dark
 * theme inverts cell backgrounds but leaves images alone. An HTML row would flip
 * its ground dark while a transparent logo's dark ink stayed dark, and the mark
 * would disappear for a large share of recipients. A full-bleed opaque band has
 * no surrounding ground that can mismatch it.
 *
 * <p>Pure apart from reading its own classpath artwork: bytes in, bytes out, no
 * configuration and no network. That is what lets the e-mail preview task and
 * the tests render a band without a server.
 */
@Slf4j
public final class MailBandComposer {

	/** Rendered width; {@code _layout.html} displays the band at 600px. */
	public static final int WIDTH = 1200;

	/** Rendered height; displayed at {@value #DISPLAY_HEIGHT}px. */
	public static final int HEIGHT = 236;

	/**
	 * The height the band is displayed at — one height for every mail, branded or
	 * not. A taller band on some mails and not others makes an inbox look like two
	 * products.
	 */
	public static final int DISPLAY_HEIGHT = 118;

	/** Backdrop used by any mail without artwork of its own. */
	public static final String DEFAULT_BACKDROP = "default";

	private static final String BACKDROP_PATH = "email/backdrop/";

	private static final Color NAVY = new Color(0x21, 0x1F, 0x3D);
	private static final Color HEX_INK = new Color(0xFF, 0xFF, 0xFF, 13);
	private static final Color GLOW = new Color(0xD9, 0xA0, 0x32, 48);
	private static final Color TRANSPARENT_AMBER = new Color(0xD9, 0xA0, 0x32, 0);
	private static final Color AMBER = new Color(0xD9, 0xA0, 0x32);
	private static final Color PLATE = new Color(0xF7, 0xF6, 0xF2);
	private static final Color WORDMARK = new Color(0xF4, 0xF3, 0xEF);

	/** The amber hairline that closes the band, at render scale. */
	private static final int RULE_H = 6;

	/** Left inset of the lockup. */
	private static final int INSET = 56;

	/** How far right the navy scrim stays fully opaque before falling off. */
	private static final double SCRIM_SOLID = 0.34;
	private static final double SCRIM_END = 0.72;

	private static final int LOGO_MAX_H = 84;
	private static final int LOGO_MAX_W = 300;

	/** Gap between the mark and the name beside it. */
	private static final int LOCKUP_GAP = 22;

	/**
	 * Never blow a small source up past this. A 32px favicon stretched to band
	 * height is mush, and mush reads worse than a small, crisp mark.
	 */
	private static final double MAX_UPSCALE = 2.0;

	/** Below this mean luminance a logo's ink would vanish into the navy. */
	private static final double DARK_INK_LUMINANCE = 0.42;

	/** Smaller than this on either edge and there is no mark worth showing. */
	private static final int MIN_SOURCE_EDGE = 8;

	private static final float NAME_MAX_SIZE = 46f;
	private static final float NAME_MIN_SIZE = 26f;

	/** Decoded once each: artwork is 30-60 KB and the font read is not cheap. */
	private static final ConcurrentHashMap<String, Optional<BufferedImage>> BACKDROPS =
			new ConcurrentHashMap<>();

	private static volatile Font brandFont;

	private MailBandComposer() {
	}

	/**
	 * The band for an instance that configured a logo: [logoPng] and [name] on the
	 * [backdrop] illustration. Empty when the bytes are not a usable raster, in
	 * which case the caller falls back to the Hinata band.
	 *
	 * <p>The name is drawn beside the mark the way "hinata" sits beside the hex on
	 * our own masthead — a logo alone leaves the recipient without the one thing
	 * the band exists to say, which is whose mail this is. It is fitted to the
	 * space that is left, and dropped rather than crushed when a very wide logo
	 * has taken the room.
	 */
	public static Optional<byte[]> composeOrganization(byte[] logoPng, String name,
			String backdrop) {
		BufferedImage logo = OrganizationLogoService.decode(logoPng).orElse(null);
		if (logo == null || logo.getWidth() < MIN_SOURCE_EDGE
				|| logo.getHeight() < MIN_SOURCE_EDGE) {
			return Optional.empty();
		}
		return render(backdrop, g -> organizationLockup(g, logo, name));
	}

	/** The band carrying Hinata's own lockup, on the [backdrop] illustration. */
	public static Optional<byte[]> composeHinata(String backdrop) {
		String key = (backdrop == null || backdrop.isBlank()) ? DEFAULT_BACKDROP : backdrop;
		return HINATA_BANDS.computeIfAbsent(key,
				name -> render(name, MailBandComposer::hinataLockup));
	}

	/**
	 * Whether artwork exists for [backdrop]. A template without its own picture is
	 * a normal state — the composer falls back to the neutral band — so this is a
	 * check, not a precondition; the tests use it to catch a template that was
	 * added without artwork.
	 */
	public static boolean hasBackdrop(String backdrop) {
		return backdrop(backdrop).isPresent();
	}

	/** The content type {@link #composeOrganization} and {@link #composeHinata} return. */
	public static final String CONTENT_TYPE = "image/jpeg";

	/**
	 * JPEG, not PNG: the band is a photographic illustration now, and a copy of it
	 * travels inside every message that is sent. Lossless would roughly quintuple
	 * the size of a transactional mail for no visible gain.
	 *
	 * <p>Quality is tuned for that same reason. The band is displayed at 600x118
	 * and rendered at twice that, so it is already being downsampled by every
	 * client — detail spent above ~0.8 is detail no recipient can see, on an
	 * attachment that goes out with every notification an instance sends.
	 */
	private static byte[] encodeJpeg(BufferedImage image) throws Exception {
		var writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
		var params = writer.getDefaultWriteParam();
		params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
		params.setCompressionQuality(0.8f);
		var bytes = new java.io.ByteArrayOutputStream();
		// MemoryCache, not ImageIO's default: that one spools every encode through
		// a temp file on disk.
		try (var out = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/**
	 * Hinata's own band for [backdrop]. Cached: it depends on nothing but the
	 * artwork, and every unbranded instance sends it with every mail.
	 */
	private static final ConcurrentHashMap<String, Optional<byte[]>> HINATA_BANDS =
			new ConcurrentHashMap<>();

	// --- painting -------------------------------------------------------------

	private interface Lockup {
		void paint(Graphics2D g);
	}

	private static Optional<byte[]> render(String backdrop, Lockup lockup) {
		try {
			// RGB, not ARGB: the band is deliberately opaque so no mail client's
			// background — light, dark or inverted — can show through it.
			BufferedImage band = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = band.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

				g.setColor(NAVY);
				g.fillRect(0, 0, WIDTH, HEIGHT);
				BufferedImage art = backdrop(backdrop).orElse(null);
				if (art != null) {
					drawCovering(g, art);
					scrim(g);
				}
				else {
					// No artwork for this mail: the honeycomb and bloom that the
					// plain masthead carries stand in, so the band still reads as
					// designed rather than as a flat rectangle.
					honeycomb(g);
					glow(g);
				}
				lockup.paint(g);

				g.setColor(AMBER);
				g.fillRect(0, HEIGHT - RULE_H, WIDTH, RULE_H);
			}
			finally {
				g.dispose();
			}
			return Optional.of(encodeJpeg(band));
		}
		catch (Exception ex) {
			log.warn("Composing the mail band failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	/** Draws [art] to cover the band, centred, preserving its aspect ratio. */
	private static void drawCovering(Graphics2D g, BufferedImage art) {
		double scale = Math.max(WIDTH / (double) art.getWidth(),
				HEIGHT / (double) art.getHeight());
		int w = (int) Math.ceil(art.getWidth() * scale);
		int h = (int) Math.ceil(art.getHeight() * scale);
		g.drawImage(art, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
	}

	/**
	 * Lays navy over the left of the band so the lockup reads against it whatever
	 * the illustration does there. Solid to {@value #SCRIM_SOLID} of the width,
	 * then a long fall-off — a hard edge would read as a pasted-on rectangle,
	 * which is exactly what the existing hand-made mastheads avoid.
	 */
	private static void scrim(Graphics2D g) {
		int solid = (int) (WIDTH * SCRIM_SOLID);
		int end = (int) (WIDTH * SCRIM_END);
		g.setColor(NAVY);
		g.fillRect(0, 0, solid, HEIGHT);
		g.setPaint(new GradientPaint(solid, 0, NAVY, end, 0, new Color(0x21, 0x1F, 0x3D, 0)));
		g.fillRect(solid, 0, end - solid, HEIGHT);
	}

	/** The faint hexagon lattice the plain Hinata masthead carries. */
	private static void honeycomb(Graphics2D g) {
		final double radius = 74;
		final double stepX = radius * 1.5;
		final double stepY = Math.sqrt(3) * radius;
		g.setColor(HEX_INK);
		g.setStroke(new BasicStroke(2f));
		int column = 0;
		for (double cx = -radius; cx < WIDTH + radius; cx += stepX, column++) {
			double offset = (column % 2 == 0) ? 0 : stepY / 2;
			for (double cy = -stepY; cy < HEIGHT + stepY; cy += stepY) {
				g.draw(hexagon(cx, cy + offset, radius));
			}
		}
	}

	private static Path2D hexagon(double cx, double cy, double r) {
		Path2D path = new Path2D.Double();
		for (int i = 0; i < 6; i++) {
			double angle = Math.toRadians(60.0 * i);
			double x = cx + r * Math.cos(angle);
			double y = cy + r * Math.sin(angle);
			if (i == 0) {
				path.moveTo(x, y);
			}
			else {
				path.lineTo(x, y);
			}
		}
		path.closePath();
		return path;
	}

	/** The warm bloom on the right, so a bare band is not flat. */
	private static void glow(Graphics2D g) {
		g.setPaint(new RadialGradientPaint(new Point2D.Float(WIDTH * 0.84f, HEIGHT * 0.5f),
				WIDTH * 0.32f, new float[] { 0f, 1f }, new Color[] { GLOW, TRANSPARENT_AMBER }));
		g.fillRect(WIDTH / 2, 0, WIDTH / 2, HEIGHT);
	}

	// --- lockups --------------------------------------------------------------

	/**
	 * The organization's mark and name. The mark is contained in a box, so an
	 * arbitrary aspect ratio — a 6:1 wordmark as readily as a square signet — is
	 * bounded rather than stretched or cropped.
	 *
	 * <p>A logo whose ink is dark would sink into the navy, so one that reads dark
	 * gets a light plate behind it. That is a design element rather than a patch:
	 * a badge on a brand band is a shape people already read as "their mark on our
	 * stationery".
	 */
	private static void organizationLockup(Graphics2D g, BufferedImage logo, String name) {
		double scale = Math.min(
				Math.min(LOGO_MAX_H / (double) logo.getHeight(), LOGO_MAX_W / (double) logo.getWidth()),
				MAX_UPSCALE);
		int w = Math.max(1, (int) Math.round(logo.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(logo.getHeight() * scale));
		int x = INSET;
		// Centred on the band above the hairline, so the optical weight sits where
		// the Hinata lockup's does.
		int y = (HEIGHT - RULE_H - h) / 2;

		boolean plated = meanInkLuminance(logo) < DARK_INK_LUMINANCE;
		if (plated) {
			int pad = 16;
			g.setColor(PLATE);
			g.fill(new RoundRectangle2D.Double(x - pad, y - pad, w + pad * 2.0, h + pad * 2.0, 26, 26));
		}
		g.drawImage(logo, x, y, w, h, null);

		if (name == null || name.isBlank()) {
			return;
		}
		int nameX = x + w + (plated ? 16 : 0) + LOCKUP_GAP;
		// Only the scrimmed part of the band is a safe ground for white text.
		int available = (int) (WIDTH * SCRIM_END) - nameX - INSET / 2;
		drawFittedName(g, name.trim(), nameX, available);
	}

	/**
	 * Draws [name] at the largest size that fits [available], shrinking to
	 * {@value #NAME_MIN_SIZE} before giving up. A name that still does not fit is
	 * dropped rather than ellipsised: half an organization's name is worse than
	 * the logo standing on its own, which already identifies them.
	 */
	private static void drawFittedName(Graphics2D g, String name, int x, int available) {
		Font base = brandFont();
		if (base == null || available < 80) {
			return;
		}
		FontRenderContext frc = g.getFontRenderContext();
		for (float size = NAME_MAX_SIZE; size >= NAME_MIN_SIZE; size -= 1f) {
			Font font = base.deriveFont(Font.BOLD, size);
			Rectangle2D bounds = font.getStringBounds(name, frc);
			if (bounds.getWidth() <= available) {
				g.setFont(font);
				g.setColor(WORDMARK);
				// Baseline from the cap-height centre, so the word sits optically
				// level with the mark rather than sinking under its descenders.
				int baseline = (int) Math.round(
						(HEIGHT - RULE_H) / 2.0 - bounds.getCenterY());
				g.drawString(name, x, baseline);
				return;
			}
		}
	}

	/**
	 * Hinata's own lockup: the stroked hex signet and the wordmark, with the short
	 * amber rule the brand carries under it. Drawn rather than composited from a
	 * baked image so it lands on every backdrop at the same size and position the
	 * organization's mark does.
	 */
	private static void hinataLockup(Graphics2D g) {
		final int markSize = 84;
		int x = INSET;
		int y = (HEIGHT - RULE_H - markSize) / 2;
		drawHexMark(g, x, y, markSize);

		Font base = brandFont();
		if (base == null) {
			return;
		}
		Font font = base.deriveFont(Font.BOLD, 52f);
		FontRenderContext frc = g.getFontRenderContext();
		Rectangle2D bounds = font.getStringBounds("hinata", frc);
		int textX = x + markSize + LOCKUP_GAP;
		int baseline = (int) Math.round((HEIGHT - RULE_H) / 2.0 - bounds.getCenterY());
		g.setFont(font);
		g.setColor(WORDMARK);
		g.drawString("hinata", textX, baseline);

		// The short amber underline under the first glyphs, as on the artwork.
		g.setColor(AMBER);
		g.fillRect(textX, baseline + 14, (int) (bounds.getWidth() * 0.28), 5);
	}

	/**
	 * The hex signet: a stroked pointy-top hexagon with a centre bar, in the
	 * app's own design space (viewBox 0 0 120 120, stroke width 11).
	 */
	private static void drawHexMark(Graphics2D g, int x, int y, int size) {
		double s = size / 120.0;
		Path2D outline = new Path2D.Double();
		outline.moveTo(x + 60 * s, y + 14 * s);
		outline.lineTo(x + 99.8 * s, y + 37 * s);
		outline.lineTo(x + 99.8 * s, y + 83 * s);
		outline.lineTo(x + 60 * s, y + 106 * s);
		outline.lineTo(x + 20.2 * s, y + 83 * s);
		outline.lineTo(x + 20.2 * s, y + 37 * s);
		outline.closePath();

		g.setColor(AMBER);
		g.setStroke(new BasicStroke((float) (11 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(outline);
		g.draw(new java.awt.geom.Line2D.Double(
				x + 20.2 * s, y + 60 * s, x + 99.8 * s, y + 60 * s));
	}

	// --- resources ------------------------------------------------------------

	private static Optional<BufferedImage> backdrop(String name) {
		String key = (name == null || name.isBlank()) ? DEFAULT_BACKDROP : name;
		return BACKDROPS.computeIfAbsent(key, MailBandComposer::readBackdrop);
	}

	private static Optional<BufferedImage> readBackdrop(String name) {
		try (InputStream in = new ClassPathResource(BACKDROP_PATH + name + ".jpg").getInputStream()) {
			return Optional.ofNullable(javax.imageio.ImageIO.read(in));
		}
		catch (Exception ex) {
			// Missing artwork degrades to the plain honeycomb band, never to a
			// failed send — and a template that has no picture of its own is a
			// normal state, not an error, so this is not logged as one.
			return Optional.empty();
		}
	}

	/**
	 * Sora, the display face the app and the masthead artwork already use, loaded
	 * from the classpath so an inbox and the product read as one thing. Null when
	 * it cannot be loaded, in which case the wordmark is simply omitted rather
	 * than drawn in whatever the host happens to have.
	 */
	private static Font brandFont() {
		Font cached = brandFont;
		if (cached != null) {
			return cached;
		}
		synchronized (MailBandComposer.class) {
			if (brandFont == null) {
				try (InputStream in =
						new ClassPathResource("email/font/Sora-Variable.ttf").getInputStream()) {
					brandFont = Font.createFont(Font.TRUETYPE_FONT, in);
				}
				catch (Exception ex) {
					log.warn("Brand font unavailable for the mail band: {}", ex.getMessage());
				}
			}
			return brandFont;
		}
	}

	/**
	 * Mean relative luminance across the logo's <em>opaque</em> pixels. Transparent
	 * ones are skipped deliberately: averaging them in would drag any logo with a
	 * generous transparent margin toward "dark" regardless of its actual ink.
	 * Sampled on a grid rather than per pixel — this decides one boolean.
	 */
	private static double meanInkLuminance(BufferedImage image) {
		long samples = 0;
		double sum = 0;
		int stepX = Math.max(1, image.getWidth() / 64);
		int stepY = Math.max(1, image.getHeight() / 64);
		for (int y = 0; y < image.getHeight(); y += stepY) {
			for (int x = 0; x < image.getWidth(); x += stepX) {
				int argb = image.getRGB(x, y);
				if (((argb >>> 24) & 0xFF) < 32) {
					continue;
				}
				double r = ((argb >> 16) & 0xFF) / 255.0;
				double gr = ((argb >> 8) & 0xFF) / 255.0;
				double b = (argb & 0xFF) / 255.0;
				sum += 0.2126 * r + 0.7152 * gr + 0.0722 * b;
				samples++;
			}
		}
		// Fully transparent: nothing would read on navy either, so give it the plate.
		return samples == 0 ? 0 : sum / samples;
	}
}
