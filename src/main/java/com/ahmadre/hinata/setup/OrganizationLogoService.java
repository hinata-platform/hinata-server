package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.Set;

/**
 * Organization-logo pipeline. The admin can either point {@code General.logoUrl}
 * at an external image URL (unchanged) or upload a file here, which is decoded
 * (validating it is a real raster image and stripping any metadata), downscaled
 * to at most {@value #MAX_EDGE}px on its longest edge while preserving aspect
 * ratio and transparency, re-encoded as PNG, and stored under a single
 * deterministic key so re-uploads overwrite cleanly with no orphans.
 *
 * <p>An uploaded logo lives in the private bucket; {@code logoUrl} is then set to
 * an <em>internal</em> proxy path ({@link #INTERNAL_URL_PREFIX}) rather than an
 * absolute URL. {@code MetaController#logo()} streams those bytes back
 * same-origin. That keeps a single source of truth ({@code logoUrl}) and one
 * download endpoint for both the "URL" and "upload" cases.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationLogoService {

	/** Single deterministic key: a re-upload overwrites, so no orphans accrue. */
	static final String LOGO_OBJECT_KEY = "branding/logo.png";

	/** Longest edge of the stored logo; plenty for headers, PDFs and retina. */
	static final int MAX_EDGE = 1024;

	/** Max accepted upload before normalization. */
	private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

	/**
	 * Formats the JDK's ImageIO decodes natively. WebP/SVG are intentionally
	 * excluded (no built-in decoder) — those stay available via the URL field.
	 */
	private static final Set<String> ACCEPTED =
			Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp");

	/** The relative proxy path an uploaded logo's {@code logoUrl} points at. */
	static final String INTERNAL_URL_PREFIX = "/api/v1/meta/logo";

	private final StorageService storage;
	private final SettingsService settings;

	/**
	 * Whether {@code logoUrl} refers to an uploaded logo (an internal proxy path)
	 * rather than an external absolute URL. Blank counts as "no uploaded logo".
	 */
	public static boolean isInternal(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		String u = url.trim().toLowerCase();
		return !u.startsWith("http://") && !u.startsWith("https://");
	}

	/** Normalizes + stores [file] as the org logo and returns its internal URL. */
	public String store(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("error.logo.empty");
		}
		if (file.getSize() > MAX_UPLOAD_BYTES) {
			throw ApiException.badRequest("error.logo.tooLarge");
		}
		String contentType = file.getContentType();
		if (contentType == null || !ACCEPTED.contains(contentType.toLowerCase())) {
			throw ApiException.badRequest("error.logo.unsupportedType");
		}

		byte[] png = normalize(file);
		// The bucket stays private; bytes are served back through the /meta/logo
		// proxy, so storage credentials never leave the server.
		storage.putObject(LOGO_OBJECT_KEY, png, "image/png");

		String url = urlFor();
		ServerSettings current = settings.get();
		current.getGeneral().setLogoUrl(url);
		settings.save(current);
		return url;
	}

	/**
	 * Removes an uploaded logo: deletes the stored object and clears
	 * {@code logoUrl} when it still points at the internal proxy. An external URL
	 * is left untouched (it is not an "upload"; the URL field owns it).
	 */
	public void remove() {
		storage.delete(LOGO_OBJECT_KEY);
		ServerSettings current = settings.get();
		if (isInternal(current.getGeneral().getLogoUrl())) {
			current.getGeneral().setLogoUrl(null);
			settings.save(current);
		}
	}

	/**
	 * Deletes just the stored object (not {@code logoUrl}). Used when a settings
	 * PUT switches the logo to an external URL or clears it, so the uploaded file
	 * can't shadow the new URL in the proxy and doesn't linger as an orphan.
	 */
	public void deleteStoredObject() {
		storage.delete(LOGO_OBJECT_KEY);
	}

	/** The uploaded logo bytes, or empty when none / storage isn't configured. */
	public Optional<StorageService.StoredObject> load() {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		return storage.getObject(LOGO_OBJECT_KEY);
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor() {
		return INTERNAL_URL_PREFIX + "?v=" + System.currentTimeMillis();
	}

	// --- image pipeline -------------------------------------------------------

	private byte[] normalize(MultipartFile file) {
		try {
			BufferedImage source = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
			if (source == null) {
				throw ApiException.badRequest("error.logo.unreadable");
			}
			return encodePng(downscaleIfNeeded(source));
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Logo normalization failed: {}", ex.getMessage());
			throw ApiException.badRequest("error.logo.unreadable");
		}
	}

	/**
	 * Downscales to at most {@link #MAX_EDGE}px on the longest edge, preserving
	 * aspect ratio (logos are rarely square, so no cropping) and the alpha channel
	 * (drawn onto a transparent ARGB canvas). Smaller sources are kept as-is.
	 */
	private BufferedImage downscaleIfNeeded(BufferedImage src) {
		int w = src.getWidth();
		int h = src.getHeight();
		int longest = Math.max(w, h);
		if (longest <= MAX_EDGE) {
			return src;
		}
		double scale = (double) MAX_EDGE / longest;
		int tw = Math.max(1, (int) Math.round(w * scale));
		int th = Math.max(1, (int) Math.round(h * scale));
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, 0, 0, tw, th, null);
		g.dispose();
		return out;
	}

	private byte[] encodePng(BufferedImage image) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", bytes)) {
			throw ApiException.badRequest("error.logo.unreadable");
		}
		return bytes.toByteArray();
	}
}
