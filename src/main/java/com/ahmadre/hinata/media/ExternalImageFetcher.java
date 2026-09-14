package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.CappedBody;
import com.ahmadre.hinata.common.PublicAddresses;
import com.ahmadre.hinata.common.PublicDns;
import com.ahmadre.hinata.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fetches an external image URL <b>server-side</b> so the browser can render it
 * without hitting cross-origin (CORS) failures. Flutter web's CanvasKit taints
 * on a cross-origin {@code <img>} and silently drops it, so images pasted by URL
 * never appear. The bytes are proxied back through our own origin instead.
 *
 * <p>Fetching an arbitrary user-supplied URL is a classic SSRF vector (OWASP
 * A10), so this is deliberately locked down:
 * <ul>
 *   <li>only {@code http}/{@code https} schemes;</li>
 *   <li>every resolved IP (all A/AAAA records) must be on the public internet as
 *       {@link PublicAddresses} defines it: loopback, link-local and the cloud
 *       metadata IP, private and CGNAT ranges, unique-local IPv6, multicast,
 *       wildcard and the IPv6 forms that wrap one of them are all rejected;</li>
 *   <li>the connection goes to exactly the addresses that were checked
 *       ({@link PublicDns}), so a rebinding DNS server gets no second question,
 *       and no proxy or pooled connection sits in between;</li>
 *   <li>a host written as an address is checked here before the request, because
 *       OkHttp connects to it without a lookup, and only its plain spelling is
 *       taken: {@code 127.1}, {@code 2130706433} and {@code .} are refused;</li>
 *   <li>redirects are followed manually (max {@value #MAX_REDIRECTS}) so each
 *       hop's host is checked again, and an allowed host can't 302 to an internal
 *       one;</li>
 *   <li>the response must be an allow-listed image type (see {@link #RASTER_TYPES});</li>
 *   <li>the body is read against a hard 10 MB cap ({@link CappedBody}), and the
 *       whole fetch, redirects included, has {@link #TIMEOUT}.</li>
 * </ul>
 * This is not only reachable from authenticated flows: the organization logo lands
 * here from the public {@code /api/v1/meta/logo}, so anything added to this class
 * has to stay safe for an anonymous caller.
 */
@Slf4j
@Component
public class ExternalImageFetcher implements DisposableBean {

	private static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final int MAX_REDIRECTS = 3;
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);

	/** The longest silence while waiting for the headers or for the next part of the body. */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	/** Everything one fetch may take, redirects included: lookups, connections, headers and body. */
	static final Duration TIMEOUT = Duration.ofSeconds(25);

	/**
	 * Raster images only; {@code image/svg+xml} is excluded. This is the set every
	 * caller that will <em>decode</em> the bytes must use: an SVG is a document, not
	 * a bitmap, and no decoder in this process should ever be pointed at one.
	 */
	public static final Set<String> RASTER_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/**
	 * {@link #RASTER_TYPES} plus the vector and icon types a browser renders
	 * safely. Only for callers that hand the bytes straight to a client and serve
	 * them under a locked-down CSP, see {@code MetaController#logo()}. Never for a
	 * caller that decodes or rasterizes them server-side.
	 */
	public static final Set<String> DISPLAY_TYPES = Set.of("image/png", "image/jpeg",
			"image/gif", "image/webp", "image/svg+xml", "image/avif", "image/x-icon",
			"image/vnd.microsoft.icon");

	private final PublicDns dns;
	private final OkHttpClient client;
	private final Duration timeout;

	public ExternalImageFetcher() {
		this(PublicDns.Resolver.SYSTEM, null, TIMEOUT);
	}

	/** For tests: [sockets] replaces the platform's sockets unless it is null. */
	ExternalImageFetcher(PublicDns.Resolver resolver, SocketFactory sockets, Duration timeout) {
		this.dns = new PublicDns("image-lookup", resolver, LOOKUP_TIMEOUT);
		this.timeout = timeout;
		OkHttpClient.Builder builder = dns.clientBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.readTimeout(READ_TIMEOUT)
				.writeTimeout(READ_TIMEOUT);
		if (sockets != null) {
			builder.socketFactory(sockets);
		}
		this.client = builder.build();
	}

	/** Fetches [rawUrl] and returns its validated raster image bytes + content type. */
	public StorageService.StoredObject fetch(String rawUrl) {
		return fetch(rawUrl, RASTER_TYPES);
	}

	/**
	 * Fetches [rawUrl], accepting only the given content types. Callers pass
	 * {@link #RASTER_TYPES} unless they exclusively pass the bytes through to a
	 * client, in which case {@link #DISPLAY_TYPES} widens it to SVG and friends.
	 */
	public StorageService.StoredObject fetch(String rawUrl, Set<String> allowedTypes) {
		HttpUrl url = requireHttp(rawUrl == null ? null : HttpUrl.parse(rawUrl.strip()));
		long deadline = System.nanoTime() + timeout.toNanos();
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			requirePublicLiteral(url);
			try (Response response = send(url, deadline)) {
				int status = response.code();
				if (status >= 300 && status < 400) {
					String location = response.header("Location");
					if (location == null) {
						throw fetchFailed();
					}
					url = requireHttp(url.resolve(location));
					continue;
				}
				if (status != 200) {
					throw fetchFailed();
				}
				String contentType = baseType(response.header("Content-Type"));
				if (!allowedTypes.contains(contentType)) {
					throw ApiException.badRequest("error.media.notAnImage");
				}
				byte[] bytes = CappedBody.read(response, MAX_BYTES);
				if (bytes.length == 0) {
					throw fetchFailed();
				}
				return new StorageService.StoredObject(bytes, contentType);
			}
			catch (CappedBody.TooLarge ex) {
				throw ApiException.badRequest("error.media.tooLarge");
			}
			catch (PublicDns.NotPublic ex) {
				throw notAllowed(url);
			}
			catch (PublicDns.SlowLookup ex) {
				throw fetchFailed();
			}
			catch (UnknownHostException ex) {
				throw ApiException.badRequest("error.media.urlNotAllowed");
			}
			catch (IOException ex) {
				throw fetchFailed();
			}
		}
		throw fetchFailed();
	}

	@Override
	public void destroy() {
		dns.shutdown();
		client.connectionPool().evictAll();
	}

	/** One hop, within what is left of the fetch's time. */
	private Response send(HttpUrl url, long deadline) throws IOException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw fetchFailed();
		}
		Call call = client.newCall(new Request.Builder().url(url).get()
				.header("User-Agent", "hinata")
				.header("Accept", "image/*")
				.header("Accept-Encoding", "identity")
				.build());
		call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
		return call.execute();
	}

	/** An http or https address with a host; OkHttp parses nothing else into an HttpUrl. */
	private static HttpUrl requireHttp(HttpUrl url) {
		if (url == null || url.host().isEmpty()) {
			throw ApiException.badRequest("error.media.urlInvalid");
		}
		return url;
	}

	/**
	 * OkHttp connects to a host written as an address without a lookup, so such a host
	 * is checked here, and only in its plain spelling. A name is checked by the lookup.
	 */
	private static void requirePublicLiteral(HttpUrl url) {
		String host = url.host();
		if (PublicDns.looksLikeAddress(host)
				&& !PublicDns.plainAddress(host).map(PublicAddresses::isPublic).orElse(false)) {
			throw notAllowed(url);
		}
	}

	private static ApiException notAllowed(HttpUrl url) {
		log.warn("Blocked SSRF-prone image proxy target: {}", url.host());
		return ApiException.badRequest("error.media.urlNotAllowed");
	}

	private static String baseType(String header) {
		if (header == null) {
			return "";
		}
		int semicolon = header.indexOf(';');
		return (semicolon >= 0 ? header.substring(0, semicolon) : header).trim().toLowerCase(Locale.ROOT);
	}

	private static ApiException fetchFailed() {
		return ApiException.badRequest("error.media.fetchFailed");
	}
}
