package com.ahmadre.hinata.ics;

import com.ahmadre.hinata.common.CappedBody;
import com.ahmadre.hinata.common.PublicAddresses;
import com.ahmadre.hinata.common.PublicDns;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Serial;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fetches an external calendar from an address a person or an administrator typed.
 *
 * <p>That is a server-side request to a URL somebody else chose, the classic SSRF
 * shape, so this is built as if anyone on the internet could hand it the URL,
 * whatever the caller in front of it checks:
 *
 * <ul>
 * <li><b>Only https</b>, on port 443 or 8443; {@code webcal://} is read as https.
 * No user name or password in the address. Calendar services put their token in
 * the query, which is accepted and never written anywhere.</li>
 * <li><b>The host must be a name.</b> Anything OkHttp reads as an address literal
 * is refused, because OkHttp connects to a literal without a lookup. An operator can
 * narrow the hosts further with {@code hinata.ics.allowed-hosts} and
 * {@code denied-hosts}; neither list can open what the address check closes.</li>
 * <li><b>Resolve once, check every answer, connect to exactly those</b>, through
 * {@link PublicDns}. The host is refused if any answer is off the public internet
 * ({@link PublicAddresses}), and there is no second lookup a rebinding DNS server
 * could answer differently. No proxy, no pooled connection.</li>
 * <li><b>No redirects</b>, not even to the same host. A 3xx is an error, so a
 * public host cannot point the request inward.</li>
 * <li><b>Ten seconds for everything</b>: lookup, connection and body. The body is
 * read against a 2 MB cap and abandoned at the cap, not measured afterwards
 * ({@link CappedBody}). {@code Accept-Encoding: identity} is sent, but iCloud answers
 * with gzip all the same, so gzip is unpacked, with the packed and the unpacked bytes
 * both counted. Any other encoding is refused.</li>
 * <li><b>HTTP/1.1 only</b>, whose response headers OkHttp caps at 256 KB.</li>
 * </ul>
 *
 * <p>Fetches run on four threads of their own behind a short queue, never on the
 * scheduler that carries the SSE heartbeats; a full queue answers
 * {@link IcsFetchError#BUSY} at once. Every refusal and failure is a result rather
 * than an exception, and neither results nor log lines carry the address, because
 * the address is a credential.
 */
@Slf4j
@Component
public class IcsFetcher implements DisposableBean {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final int THREADS = 4;
	private static final int QUEUE = 32;
	private static final int MAX_URL_LENGTH = 2048;
	private static final int MAX_VALIDATOR_LENGTH = 256;
	private static final Set<Integer> PORTS = Set.of(443, 8443);
	private static final List<String> WEBCAL = List.of("webcal://", "webcals://");

	private final List<String> allowedHosts;
	private final List<String> deniedHosts;
	private final ExecutorService fetches;
	private final PublicDns dns;
	private final OkHttpClient client;

	@Autowired
	public IcsFetcher(HinataProperties properties) {
		this(properties.getIcs(), PublicDns.Resolver.SYSTEM, Transport.SYSTEM, TIMEOUT,
				pool("ics-fetch", THREADS, QUEUE));
	}

	IcsFetcher(HinataProperties.Ics config, PublicDns.Resolver resolver, Transport transport, Duration timeout,
			ExecutorService fetches) {
		this.allowedHosts = hostPatterns("allowed-hosts", config.getAllowedHosts());
		this.deniedHosts = hostPatterns("denied-hosts", config.getDeniedHosts());
		this.fetches = fetches;
		// The lookup is bounded by the fetch's time too: the call timeout cannot interrupt it.
		this.dns = new PublicDns("ics-lookup", resolver, timeout);
		OkHttpClient.Builder builder = dns.clientBuilder()
				.callTimeout(timeout)
				.connectTimeout(timeout)
				.readTimeout(timeout)
				.writeTimeout(timeout);
		if (transport.sockets() != null) {
			builder.socketFactory(transport.sockets());
		}
		if (transport.tls() != null) {
			builder.sslSocketFactory(transport.tls(), transport.trust());
		}
		this.client = builder.build();
	}

	/**
	 * Fetches the calendar at [url] on the fetcher's own threads. [etag] and
	 * [lastModified] are what the previous fetch returned, or null; with either, an
	 * unchanged calendar comes back as {@link IcsFetchResult.Outcome#NOT_MODIFIED}.
	 * The future always completes normally.
	 *
	 * <p>The future can take longer than the fetch itself while other fetches wait in the
	 * queue. Never {@code join()} it on a request or scheduler thread, and parse what it
	 * brings on an executor of your own, not on the fetcher's threads.
	 */
	public CompletableFuture<IcsFetchResult> fetch(String url, String etag, String lastModified) {
		try {
			return CompletableFuture.supplyAsync(() -> fetchNow(url, etag, lastModified), fetches);
		}
		catch (RejectedExecutionException ex) {
			return CompletableFuture.completedFuture(IcsFetchResult.failed(IcsFetchError.BUSY));
		}
	}

	/**
	 * The address rules alone, without resolving or connecting, so that an unusable
	 * address can be refused where it is entered. Empty when nothing about the
	 * address itself stands in the way of fetching it.
	 */
	public Optional<IcsFetchError> check(String url) {
		try {
			target(url);
			return Optional.empty();
		}
		catch (Refused refused) {
			return Optional.of(refused.error);
		}
	}

	@Override
	public void destroy() {
		fetches.shutdownNow();
		dns.shutdown();
		client.connectionPool().evictAll();
	}

	private IcsFetchResult fetchNow(String url, String etag, String lastModified) {
		IcsFetchResult result;
		try {
			result = fetchChecked(target(url), validator(etag), validator(lastModified));
		}
		catch (Refused refused) {
			result = IcsFetchResult.failed(refused.error);
		}
		if (result.error() != null) {
			log.debug("Calendar fetch failed: {} (HTTP {})", result.error(), result.httpStatus());
		}
		return result;
	}

	private IcsFetchResult fetchChecked(HttpUrl url, String etag, String lastModified) {
		Request.Builder request = new Request.Builder().url(url).get()
				.header("User-Agent", "hinata")
				.header("Accept", "text/calendar, */*;q=0.5")
				.header("Accept-Encoding", "identity");
		if (etag != null) {
			request.header("If-None-Match", etag);
		}
		if (lastModified != null) {
			request.header("If-Modified-Since", lastModified);
		}
		Call call = client.newCall(request.build());
		try (Response response = call.execute()) {
			return read(response, url, etag, lastModified);
		}
		catch (UnknownHostException ex) {
			return IcsFetchResult.failed(ex instanceof PublicDns.SlowLookup ? IcsFetchError.TIMEOUT : IcsFetchError.HOST_NOT_ALLOWED);
		}
		catch (IOException ex) {
			if (call.isCanceled() || ex instanceof InterruptedIOException) {
				return IcsFetchResult.failed(IcsFetchError.TIMEOUT);
			}
			return IcsFetchResult.failed(ex instanceof SSLException ? IcsFetchError.TLS_FAILED : IcsFetchError.UNREACHABLE);
		}
		catch (RuntimeException ex) {
			// The type only: OkHttp's messages name the host.
			log.warn("Calendar fetch failed unexpectedly: {}", ex.getClass().getName());
			return IcsFetchResult.failed(IcsFetchError.UNREACHABLE);
		}
	}

	private static IcsFetchResult read(Response response, HttpUrl url, String etag, String lastModified)
			throws IOException {
		int status = response.code();
		if (status == 304 && (etag != null || lastModified != null)) {
			String newEtag = validator(response.header("ETag"));
			String newLastModified = validator(response.header("Last-Modified"));
			return IcsFetchResult.notModified(newEtag != null ? newEtag : etag,
					newLastModified != null ? newLastModified : lastModified);
		}
		if (status >= 300 && status < 400 && status != 304) {
			return IcsFetchResult.failed(IcsFetchError.REDIRECT, status);
		}
		if (status != 200) {
			return IcsFetchResult.failed(IcsFetchError.HTTP_STATUS, status);
		}
		if (!isCalendar(response.header("Content-Type"), url)) {
			return IcsFetchResult.failed(IcsFetchError.NOT_A_CALENDAR, status);
		}
		byte[] calendar;
		try {
			calendar = CappedBody.read(response, IcsLexer.MAX_BYTES);
		}
		catch (CappedBody.TooLarge ex) {
			// Closing the response on the way out drops the connection mid-body.
			return IcsFetchResult.failed(IcsFetchError.TOO_LARGE, status);
		}
		catch (CappedBody.Unreadable ex) {
			return IcsFetchResult.failed(IcsFetchError.ENCODING, status);
		}
		if (calendar.length == 0) {
			return IcsFetchResult.failed(IcsFetchError.NOT_A_CALENDAR, status);
		}
		return IcsFetchResult.fetched(calendar, validator(response.header("ETag")),
				validator(response.header("Last-Modified")));
	}

	/** Labelled as a calendar, or named like one by a server that labels everything text/plain. */
	private static boolean isCalendar(String contentType, HttpUrl url) {
		MediaType type = contentType == null ? null : MediaType.parse(contentType);
		if (type != null && "text".equals(type.type()) && "calendar".equals(type.subtype())) {
			return true;
		}
		List<String> segments = url.pathSegments();
		return segments.get(segments.size() - 1).toLowerCase(Locale.ROOT).endsWith(".ics");
	}

	/** The address as OkHttp will use it, or a refusal naming the first rule it breaks. */
	private HttpUrl target(String rawUrl) throws Refused {
		String text = rawUrl == null ? "" : rawUrl.strip();
		if (text.isEmpty() || text.length() > MAX_URL_LENGTH) {
			throw new Refused(IcsFetchError.URL_INVALID);
		}
		for (String alias : WEBCAL) {
			if (text.regionMatches(true, 0, alias, 0, alias.length())) {
				text = "https://" + text.substring(alias.length());
				break;
			}
		}
		HttpUrl url = HttpUrl.parse(text);
		if (url == null || !url.isHttps()) {
			throw new Refused(IcsFetchError.URL_INVALID);
		}
		if (!url.username().isEmpty() || !url.password().isEmpty()) {
			throw new Refused(IcsFetchError.CREDENTIALS_IN_URL);
		}
		if (!PORTS.contains(url.port())) {
			throw new Refused(IcsFetchError.PORT_NOT_ALLOWED);
		}
		// The literal test runs on the host exactly as OkHttp will see it: stripping the
		// trailing dot first would let "." through, which OkHttp resolves without the hook.
		String host = url.host();
		if (host.isEmpty() || PublicDns.looksLikeAddress(host) || !hostAllowed(withoutTrailingDot(host))) {
			throw new Refused(IcsFetchError.HOST_NOT_ALLOWED);
		}
		return url;
	}

	private boolean hostAllowed(String host) {
		if (deniedHosts.stream().anyMatch(pattern -> matches(pattern, host))) {
			return false;
		}
		return allowedHosts.isEmpty() || allowedHosts.stream().anyMatch(pattern -> matches(pattern, host));
	}

	private static boolean matches(String pattern, String host) {
		return pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) : host.equals(pattern);
	}

	/**
	 * The operator's entries in the form {@link #target} compares against: parsed by
	 * the same URL parser, so case, a trailing dot and international names cannot
	 * make an entry miss the host it names.
	 *
	 * <p>An entry that names no host (a second wildcard, a port, a path, an address)
	 * stops the server from starting. Skipped quietly, a denied host the operator
	 * believes blocked would simply stay open.
	 */
	private static List<String> hostPatterns(String property, List<String> entries) {
		List<String> patterns = new ArrayList<>();
		for (int index = 0; entries != null && index < entries.size(); index++) {
			String entry = entries.get(index);
			if (entry == null || entry.isBlank()) {
				continue;
			}
			String value = entry.strip();
			boolean wildcard = value.startsWith("*.");
			String name = wildcard ? value.substring(2) : value;
			boolean plainName = !name.isEmpty() && name.chars().noneMatch(c -> "*/:@?#[]\\ ".indexOf(c) >= 0);
			HttpUrl parsed = plainName ? HttpUrl.parse("https://" + name + "/") : null;
			String host = parsed == null ? "" : withoutTrailingDot(parsed.host());
			if (host.isEmpty() || PublicDns.looksLikeAddress(host)) {
				throw new IllegalStateException("hinata.ics." + property + "[" + index + "] is neither a host name "
						+ "nor *. followed by one: " + value);
			}
			patterns.add(wildcard ? "*." + host : host);
		}
		return List.copyOf(patterns);
	}

	private static String withoutTrailingDot(String host) {
		return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
	}

	/** A validator worth sending back: short, printable ASCII, else nothing. */
	private static String validator(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_VALIDATOR_LENGTH) {
			return null;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return null;
			}
		}
		return value;
	}

	static ThreadPoolExecutor pool(String name, int threads, int queue) {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queue), Thread.ofPlatform().name(name + "-", 1).daemon().factory());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	/** The sockets and the trust a connection is made with; a null part means the platform's default. */
	record Transport(SocketFactory sockets, SSLSocketFactory tls, X509TrustManager trust) {
		static final Transport SYSTEM = new Transport(null, null, null);
	}

	/** An address refused before anything was resolved or sent. No message, no stack. */
	private static final class Refused extends Exception {
		@Serial
		private static final long serialVersionUID = 1L;

		private final IcsFetchError error;

		Refused(IcsFetchError error) {
			super(null, null, false, false);
			this.error = error;
		}
	}
}
