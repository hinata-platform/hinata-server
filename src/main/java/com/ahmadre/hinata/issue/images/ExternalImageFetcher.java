package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.CappedBody;
import com.ahmadre.hinata.common.PublicAddresses;
import com.ahmadre.hinata.common.PublicDns;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fetches an external image of an ingested mail for a reader who chose to load it (HIN-181), so
 * the reader's app never connects to the sender's server: the mail's trackers see this server,
 * not who reads the ticket.
 *
 * <p>The address comes from a mail, which anybody can send, so the fetch is locked down exactly
 * like {@code LogoFetcher}'s (OWASP A10):
 * <ul>
 *   <li>only {@code http}/{@code https}, only on ports 80 and 443;</li>
 *   <li>every resolved address must be public ({@link PublicAddresses}): loopback, private,
 *       link-local and the cloud metadata address, unique-local IPv6, multicast and the IPv6
 *       forms wrapping one of them are refused;</li>
 *   <li>the connection goes to exactly the addresses that were checked ({@link PublicDns}), so a
 *       rebinding name server gets no second question; no proxy, no pooled connection;</li>
 *   <li>a host written as an address is checked before the request, only in its plain
 *       spelling;</li>
 *   <li>redirects are followed by hand, at most {@value #MAX_REDIRECTS}, each hop checked
 *       again;</li>
 *   <li>no cookies, no referrer, a fixed User-Agent;</li>
 *   <li>at most {@value #MAX_BYTES} bytes, {@link #TIMEOUT} for everything, lookups and
 *       redirects included;</li>
 *   <li>only PNG, JPEG, GIF and WebP, told by their first bytes, not by what the server claims:
 *       an SVG is a document that runs in whatever shows it, and is refused.</li>
 * </ul>
 */
@Slf4j
@Component
class ExternalImageFetcher implements DisposableBean {

	static final int MAX_BYTES = 8 * 1024 * 1024;
	static final int MAX_REDIRECTS = 3;
	private static final Set<Integer> PORTS = Set.of(80, 443);
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

	/** Everything one fetch may take. */
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Bytes and the type their first bytes say they are. */
	record Image(byte[] data, String contentType) {
	}

	private final Duration timeout;
	private final PublicDns dns;
	private final OkHttpClient client;

	ExternalImageFetcher() {
		this(PublicDns.Resolver.SYSTEM, null, TIMEOUT);
	}

	/** For tests: [sockets] replaces the platform's sockets unless it is null. */
	ExternalImageFetcher(PublicDns.Resolver resolver, SocketFactory sockets, Duration timeout) {
		this.timeout = timeout;
		this.dns = new PublicDns("mail-image-lookup", resolver, LOOKUP_TIMEOUT);
		OkHttpClient.Builder builder = dns.clientBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.readTimeout(READ_TIMEOUT)
				.writeTimeout(READ_TIMEOUT);
		if (sockets != null) builder.socketFactory(sockets);
		this.client = builder.build();
	}

	/**
	 * The image at [rawUrl].
	 *
	 * @throws ApiException 400 for an address that may not be fetched, 502 when the sender's
	 *                      server gives no usable image
	 */
	Image fetch(String rawUrl) {
		HttpUrl url = target(rawUrl == null ? null : HttpUrl.parse(rawUrl.strip()));
		long deadline = System.nanoTime() + timeout.toNanos();
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			try (Response response = send(url, deadline)) {
				int status = response.code();
				if (status >= 300 && status < 400) {
					String location = response.header("Location");
					if (location == null) throw fetchFailed();
					url = target(url.resolve(location));
					continue;
				}
				if (status != 200) throw fetchFailed();
				byte[] bytes = CappedBody.read(response, MAX_BYTES);
				String type = sniff(bytes);
				if (type == null) throw ApiException.badRequest("error.media.notAnImage");
				return new Image(bytes, type);
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
		// One redirect more than allowed.
		throw fetchFailed();
	}

	@Override
	public void destroy() {
		dns.shutdown();
		client.connectionPool().evictAll();
	}

	private Response send(HttpUrl url, long deadline) throws IOException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) throw fetchFailed();
		Call call = client.newCall(new Request.Builder().url(url).get()
				.header("User-Agent", "hinata-image-proxy")
				.header("Accept", "image/png,image/jpeg,image/gif,image/webp")
				.header("Accept-Encoding", "identity")
				.build());
		call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
		return dns.within(deadline, call::execute);
	}

	/** [url] if it may be asked: http or https with a host, on port 80 or 443, no refused literal. */
	private static HttpUrl target(HttpUrl url) {
		if (url == null || url.host().isEmpty()) throw ApiException.badRequest("error.media.urlInvalid");
		String host = url.host();
		boolean refusedLiteral = PublicDns.looksLikeAddress(host)
				&& !PublicDns.plainAddress(host).map(PublicAddresses::isPublic).orElse(false);
		if (!PORTS.contains(url.port()) || refusedLiteral) throw notAllowed(url);
		return url;
	}

	/** The raster type [bytes] start like, or null: PNG, JPEG, GIF, WebP. */
	static String sniff(byte[] bytes) {
		if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
				&& bytes[4] == '\r' && bytes[5] == '\n' && bytes[6] == 0x1A && bytes[7] == '\n') {
			return "image/png";
		}
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
				&& (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
			return "image/gif";
		}
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
			return "image/webp";
		}
		return null;
	}

	private static ApiException notAllowed(HttpUrl url) {
		log.warn("Blocked SSRF-prone mail image address: {}", url.host());
		return ApiException.badRequest("error.media.urlNotAllowed");
	}

	private static ApiException fetchFailed() {
		return new ApiException(HttpStatus.BAD_GATEWAY, "error.media.fetchFailed");
	}
}
