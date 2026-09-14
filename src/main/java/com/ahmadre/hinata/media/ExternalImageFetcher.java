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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
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
 *   <li>only {@code http}/{@code https}, and only on ports 80 and 443: any other
 *       port of a public address may be one of this server's own services, reached
 *       from inside past an external firewall;</li>
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
 *       hop is checked again, and an allowed host can't 302 to an internal one;</li>
 *   <li>the response must be an allow-listed image type (see {@link #RASTER_TYPES});</li>
 *   <li>the body is read against a hard 5 MB cap ({@link CappedBody}), and the
 *       whole fetch, lookups and redirects included, has {@link #TIMEOUT}.</li>
 * </ul>
 *
 * <p>The proxy runs on request threads, so it is kept from holding them as well: at
 * most {@value #MAX_RUNNING} proxy fetches run at once and
 * {@value #MAX_RUNNING_PER_PERSON} for one person, the next one is refused at once
 * with 429, and an image fetched a moment ago is served from a small cache to
 * everybody who asks for the same address. The organization logo has lookups of its
 * own, so a busy proxy cannot keep it away.
 *
 * <p>This is not only reachable from authenticated flows: the organization logo lands
 * here from the public {@code /api/v1/meta/logo}, so anything added to this class
 * has to stay safe for an anonymous caller.
 */
@Slf4j
@Component
public class ExternalImageFetcher implements DisposableBean {

	/** What one image may weigh. Pasted images are for reading and a logo is branding; neither needs more. */
	private static final long MAX_BYTES = 5L * 1024 * 1024;
	private static final int MAX_REDIRECTS = 3;
	private static final Set<Integer> PORTS = Set.of(80, 443);
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);

	/** The longest silence while waiting for the headers or for the next part of the body. */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	/** Everything one fetch may take, lookups and redirects included. */
	static final Duration TIMEOUT = Duration.ofSeconds(25);

	private static final int MAX_RUNNING = 8;
	private static final int MAX_RUNNING_PER_PERSON = 2;

	/** What the cache of proxied images may hold, and for how long. */
	private static final long CACHE_BYTES = 32L * 1024 * 1024;
	private static final Duration CACHE_TIME = Duration.ofMinutes(15);

	/**
	 * Raster images only; {@code image/svg+xml} is excluded. This is the set every
	 * caller that will <em>decode</em> the bytes must use: an SVG is a document, not
	 * a bitmap, and no decoder in this process should ever be pointed at one.
	 */
	public static final Set<String> RASTER_TYPES =
			Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/**
	 * {@link #RASTER_TYPES} plus the vector and icon types a browser renders
	 * safely. Only for the logo, whose bytes go straight to a client under a
	 * locked-down CSP, see {@code MetaController#logo()}. Never for a caller that
	 * decodes or rasterizes them server-side.
	 */
	public static final Set<String> DISPLAY_TYPES = Set.of("image/png", "image/jpeg",
			"image/gif", "image/webp", "image/svg+xml", "image/avif", "image/x-icon",
			"image/vnd.microsoft.icon");

	private final Duration timeout;
	private final Lane proxy;
	private final Lane logo;
	private final Semaphore running = new Semaphore(MAX_RUNNING);

	/** Proxy fetches running, by person. Guarded by itself. */
	private final Map<String, Integer> runningByPerson = new HashMap<>();

	private final Cache cache = new Cache();

	public ExternalImageFetcher() {
		this(PublicDns.Resolver.SYSTEM, null, TIMEOUT);
	}

	/** For tests: [sockets] replaces the platform's sockets unless it is null. */
	ExternalImageFetcher(PublicDns.Resolver resolver, SocketFactory sockets, Duration timeout) {
		this.timeout = timeout;
		this.proxy = new Lane("image-proxy-lookup", resolver, sockets);
		this.logo = new Lane("logo-lookup", resolver, sockets);
	}

	/**
	 * The raster image at [rawUrl] for [person], fetched now or taken from the cache.
	 *
	 * @throws ApiException 429 {@code error.media.busy} while [person], or everybody, has
	 *                      as many images loading as allowed; 400 for an address that may
	 *                      not be fetched and for an answer that is no usable image
	 */
	public StorageService.StoredObject fetch(String rawUrl, String person) {
		HttpUrl url = target(rawUrl);
		String address = url.toString();
		StorageService.StoredObject cached = cache.get(address);
		if (cached != null) {
			return cached;
		}
		if (!start(person)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.media.busy");
		}
		try {
			StorageService.StoredObject image = proxy.fetch(url, RASTER_TYPES, person);
			cache.put(address, image);
			return image;
		}
		finally {
			end(person);
		}
	}

	/**
	 * The organization logo at [rawUrl], raster or vector ({@link #DISPLAY_TYPES}). For
	 * {@code BrandLogoService}, which fetches it once at a time and keeps it.
	 */
	public StorageService.StoredObject fetchLogo(String rawUrl) {
		return logo.fetch(target(rawUrl), DISPLAY_TYPES, null);
	}

	@Override
	public void destroy() {
		proxy.shutdown();
		logo.shutdown();
	}

	/** A place for [person] among the proxy fetches running, or false when there is none. */
	private boolean start(String person) {
		if (!running.tryAcquire()) {
			return false;
		}
		synchronized (runningByPerson) {
			int count = runningByPerson.getOrDefault(person, 0);
			if (count < MAX_RUNNING_PER_PERSON) {
				runningByPerson.put(person, count + 1);
				return true;
			}
		}
		running.release();
		return false;
	}

	private void end(String person) {
		synchronized (runningByPerson) {
			runningByPerson.computeIfPresent(person, (key, count) -> count == 1 ? null : count - 1);
		}
		running.release();
	}

	private static HttpUrl target(String rawUrl) {
		return target(rawUrl == null ? null : HttpUrl.parse(rawUrl.strip()));
	}

	/**
	 * [url] if this fetcher may ask it: http or https with a host, on port 80 or 443.
	 * OkHttp connects to a host written as an address without a lookup, so such a host
	 * is checked here, and only in its plain spelling. A name is checked by the lookup.
	 */
	private static HttpUrl target(HttpUrl url) {
		if (url == null || url.host().isEmpty()) {
			throw ApiException.badRequest("error.media.urlInvalid");
		}
		String host = url.host();
		boolean refusedLiteral = PublicDns.looksLikeAddress(host)
				&& !PublicDns.plainAddress(host).map(PublicAddresses::isPublic).orElse(false);
		if (!PORTS.contains(url.port()) || refusedLiteral) {
			throw notAllowed(url);
		}
		return url;
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

	/** A client with lookups of its own. */
	private final class Lane {

		private final PublicDns dns;
		private final OkHttpClient client;

		Lane(String name, PublicDns.Resolver resolver, SocketFactory sockets) {
			this.dns = new PublicDns(name, resolver, LOOKUP_TIMEOUT);
			OkHttpClient.Builder builder = dns.clientBuilder()
					.connectTimeout(CONNECT_TIMEOUT)
					.readTimeout(READ_TIMEOUT)
					.writeTimeout(READ_TIMEOUT);
			if (sockets != null) {
				builder.socketFactory(sockets);
			}
			this.client = builder.build();
		}

		StorageService.StoredObject fetch(HttpUrl first, Set<String> allowedTypes, String person) {
			long deadline = System.nanoTime() + timeout.toNanos();
			HttpUrl url = first;
			for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
				try (Response response = send(url, person, deadline)) {
					int status = response.code();
					if (status >= 300 && status < 400) {
						String location = response.header("Location");
						if (location == null) {
							throw fetchFailed();
						}
						url = target(url.resolve(location));
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

		/** One hop within what is left of the fetch's time, its lookup included. */
		private Response send(HttpUrl url, String person, long deadline) throws IOException {
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
			return dns.within(person, deadline, call::execute);
		}

		void shutdown() {
			dns.shutdown();
			client.connectionPool().evictAll();
		}
	}

	/**
	 * Proxied images by address, each for {@link #CACHE_TIME}, together within
	 * {@link #CACHE_BYTES}; the least recently read leave first. The same address gives
	 * everybody the same public bytes, so the cache is shared.
	 */
	private static final class Cache {

		private final LinkedHashMap<String, Kept> kept = new LinkedHashMap<>(16, 0.75f, true);
		private long bytes;

		synchronized StorageService.StoredObject get(String address) {
			Kept entry = kept.get(address);
			if (entry == null) {
				return null;
			}
			if (System.nanoTime() - entry.until() > 0) {
				remove(address);
				return null;
			}
			return entry.image();
		}

		synchronized void put(String address, StorageService.StoredObject image) {
			remove(address);
			kept.put(address, new Kept(image, System.nanoTime() + CACHE_TIME.toNanos()));
			bytes += image.data().length;
			Iterator<Kept> leastRecent = kept.values().iterator();
			while (bytes > CACHE_BYTES && leastRecent.hasNext()) {
				bytes -= leastRecent.next().image().data().length;
				leastRecent.remove();
			}
		}

		private void remove(String address) {
			Kept removed = kept.remove(address);
			if (removed != null) {
				bytes -= removed.image().data().length;
			}
		}

		private record Kept(StorageService.StoredObject image, long until) {
		}
	}
}
