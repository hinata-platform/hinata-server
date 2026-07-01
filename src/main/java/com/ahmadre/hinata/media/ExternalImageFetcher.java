package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Fetches an external image URL <b>server-side</b> so the browser can render it
 * without hitting cross-origin (CORS) failures — Flutter web's CanvasKit taints
 * on a cross-origin {@code <img>} and silently drops it, so images pasted by URL
 * never appear. The bytes are proxied back through our own origin instead.
 *
 * <p>Fetching an arbitrary user-supplied URL is a classic SSRF vector (OWASP
 * A10), so this is deliberately locked down:
 * <ul>
 *   <li>only {@code http}/{@code https} schemes;</li>
 *   <li>every resolved IP (all A/AAAA records) must be a public unicast address
 *       — loopback, link-local, site-local/private, CGNAT, unique-local IPv6,
 *       multicast, wildcard and the cloud metadata IP are all rejected;</li>
 *   <li>redirects are followed manually (max {@value #MAX_REDIRECTS}) so each
 *       hop's host is re-validated — an allowed host can't 302 to an internal
 *       one;</li>
 *   <li>the response must be an allow-listed raster image type (SVG excluded —
 *       it can carry script);</li>
 *   <li>the body is read with a hard {@value #MAX_BYTES}-byte cap and the whole
 *       exchange is time-boxed.</li>
 * </ul>
 * A residual DNS-rebinding TOCTOU window remains (validate → connect can re-
 * resolve); it is accepted here as low-risk for an authenticated internal tool.
 */
@Slf4j
@Component
public class ExternalImageFetcher {

	private static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final int MAX_REDIRECTS = 3;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/** Raster images only; {@code image/svg+xml} is excluded (stored-XSS risk). */
	private static final Set<String> ALLOWED_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	private final HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();

	/** Fetches [rawUrl] and returns its validated image bytes + content type. */
	public StorageService.StoredObject fetch(String rawUrl) {
		URI uri = parse(rawUrl);
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			requireSafeHost(uri);
			HttpResponse<InputStream> response = send(uri);
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				String location = response.headers().firstValue("location").orElse(null);
				close(response.body());
				if (location == null) {
					throw fetchFailed();
				}
				uri = requireHttp(uri.resolve(location));
				continue;
			}
			if (status != 200) {
				close(response.body());
				throw fetchFailed();
			}
			String contentType = response.headers().firstValue("content-type")
					.map(ExternalImageFetcher::baseType).orElse("");
			if (!ALLOWED_TYPES.contains(contentType)) {
				close(response.body());
				throw ApiException.badRequest("error.media.notAnImage");
			}
			return new StorageService.StoredObject(readCapped(response.body()), contentType);
		}
		throw fetchFailed();
	}

	private HttpResponse<InputStream> send(URI uri) {
		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(REQUEST_TIMEOUT)
					.header("Accept", "image/*")
					.GET()
					.build();
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (IOException ex) {
			throw fetchFailed();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw fetchFailed();
		}
	}

	private URI parse(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw ApiException.badRequest("error.media.urlInvalid");
		}
		try {
			return requireHttp(new URI(rawUrl.trim()));
		}
		catch (URISyntaxException ex) {
			throw ApiException.badRequest("error.media.urlInvalid");
		}
	}

	private URI requireHttp(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				|| uri.getHost() == null || uri.getHost().isBlank()) {
			throw ApiException.badRequest("error.media.urlInvalid");
		}
		return uri;
	}

	/** Rejects the request unless every resolved address is a public host. */
	private void requireSafeHost(URI uri) {
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException ex) {
			throw ApiException.badRequest("error.media.urlNotAllowed");
		}
		for (InetAddress address : addresses) {
			if (isBlocked(address)) {
				log.warn("Blocked SSRF-prone image proxy target: {} -> {}", uri.getHost(),
						address.getHostAddress());
				throw ApiException.badRequest("error.media.urlNotAllowed");
			}
		}
	}

	private static boolean isBlocked(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress()
				|| address.isLinkLocalAddress() || address.isSiteLocalAddress()
				|| address.isMulticastAddress()) {
			return true;
		}
		byte[] b = address.getAddress();
		if (b.length == 4) {
			int first = b[0] & 0xFF;
			int second = b[1] & 0xFF;
			// 169.254.0.0/16 (link-local, incl. 169.254.169.254 cloud metadata).
			if (first == 169 && second == 254) {
				return true;
			}
			// 100.64.0.0/10 — carrier-grade NAT, also used by Tailscale.
			return first == 100 && second >= 64 && second <= 127;
		}
		if (b.length == 16) {
			// fc00::/7 — IPv6 unique-local addresses (not covered by isSiteLocal).
			return (b[0] & 0xFE) == 0xFC;
		}
		return false;
	}

	private static byte[] readCapped(InputStream body) {
		try (body) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			long total = 0;
			while ((read = body.read(chunk)) != -1) {
				total += read;
				if (total > MAX_BYTES) {
					throw ApiException.badRequest("error.media.tooLarge");
				}
				out.write(chunk, 0, read);
			}
			if (out.size() == 0) {
				throw fetchFailed();
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw fetchFailed();
		}
	}

	private static String baseType(String header) {
		int semicolon = header.indexOf(';');
		return (semicolon >= 0 ? header.substring(0, semicolon) : header).trim().toLowerCase();
	}

	private static void close(InputStream stream) {
		try {
			stream.close();
		}
		catch (IOException ignored) {
			// best-effort cleanup of the discarded redirect/error body
		}
	}

	private static ApiException fetchFailed() {
		return ApiException.badRequest("error.media.fetchFailed");
	}
}
