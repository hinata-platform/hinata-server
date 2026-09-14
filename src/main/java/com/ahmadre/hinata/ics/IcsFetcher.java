package com.ahmadre.hinata.ics;

import com.ahmadre.hinata.common.PublicAddresses;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.GzipSource;
import okio.Okio;
import okio.Source;
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
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
 * is refused, because OkHttp connects to a literal without consulting the
 * {@code Dns} hook below. An operator can narrow the hosts further with
 * {@code hinata.ics.allowed-hosts} and {@code denied-hosts}; neither list can open
 * what the address check closes.</li>
 * <li><b>Resolve once, check every answer, connect to exactly those.</b> The
 * {@code Dns} hook is OkHttp's only way from a name to a socket. It resolves,
 * refuses the host if any answer is off the public internet
 * ({@link PublicAddresses}), and hands OkHttp the checked addresses, so there is
 * no second lookup a rebinding DNS server could answer differently. No proxy is
 * used: a proxy would resolve the name itself.</li>
 * <li><b>No redirects</b>, not even to the same host. A 3xx is an error, so a
 * public host cannot point the request inward.</li>
 * <li><b>Ten seconds for everything</b>: lookup, connection and body. The body is
 * read against a 2 MB cap and abandoned at the cap, not measured afterwards.
 * {@code Accept-Encoding: identity} is sent, but iCloud answers with gzip all the
 * same, so gzip is unpacked and the cap counts the unpacked bytes as well as the
 * packed ones: a small body cannot unfold into a large one. Any other encoding is
 * refused.</li>
 * <li><b>HTTP/1.1 only</b>, whose response headers OkHttp caps at 256 KB. A
 * calendar gains nothing from HTTP/2, and HTTP/2 header frames have no such cap
 * here.</li>
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

	/** OkHttp's own test for "this host is an address"; such a host never reaches the Dns hook. */
	private static final Pattern ADDRESS_LITERAL = Pattern.compile("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)");

	private final List<String> allowedHosts;
	private final List<String> deniedHosts;
	private final Resolver resolver;
	private final Duration timeout;
	private final ExecutorService fetches;
	private final ExecutorService lookups = pool("ics-lookup", THREADS, QUEUE);
	private final OkHttpClient client;

	@Autowired
	public IcsFetcher(HinataProperties properties) {
		this(properties.getIcs(), InetAddress::getAllByName, Transport.SYSTEM, TIMEOUT,
				pool("ics-fetch", THREADS, QUEUE));
	}

	IcsFetcher(HinataProperties.Ics config, Resolver resolver, Transport transport, Duration timeout,
			ExecutorService fetches) {
		this.allowedHosts = hostPatterns(config.getAllowedHosts());
		this.deniedHosts = hostPatterns(config.getDeniedHosts());
		this.resolver = resolver;
		this.timeout = timeout;
		this.fetches = fetches;
		OkHttpClient.Builder builder = new OkHttpClient.Builder()
				.dns(this::lookup)
				.proxy(Proxy.NO_PROXY)
				.protocols(List.of(Protocol.HTTP_1_1))
				.followRedirects(false)
				.followSslRedirects(false)
				.retryOnConnectionFailure(false)
				// Nothing is kept for reuse: a pooled connection would skip the lookup.
				.connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
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
		lookups.shutdownNow();
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
			return IcsFetchResult.failed(ex instanceof SlowLookup ? IcsFetchError.TIMEOUT : IcsFetchError.HOST_NOT_ALLOWED);
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
		String encoding = response.header("Content-Encoding");
		String coding = encoding == null ? "" : encoding.strip().toLowerCase(Locale.ROOT);
		boolean gzip = coding.equals("gzip") || coding.equals("x-gzip");
		if (!gzip && !coding.isEmpty() && !coding.equals("identity")) {
			return IcsFetchResult.failed(IcsFetchError.ENCODING, status);
		}
		ResponseBody body = response.body();
		if (body == null || !isCalendar(response.header("Content-Type"), url)) {
			return IcsFetchResult.failed(IcsFetchError.NOT_A_CALENDAR, status);
		}
		if (body.contentLength() > IcsLexer.MAX_BYTES) {
			return IcsFetchResult.failed(IcsFetchError.TOO_LARGE, status);
		}
		if (gzip && !looksLikeGzip(body.source())) {
			return IcsFetchResult.failed(IcsFetchError.ENCODING, status);
		}
		byte[] calendar = readCapped(body.source(), gzip);
		if (calendar == null) {
			// Closing the response on the way out drops the connection mid-body.
			return IcsFetchResult.failed(IcsFetchError.TOO_LARGE, status);
		}
		if (calendar.length == 0) {
			return IcsFetchResult.failed(IcsFetchError.NOT_A_CALENDAR, status);
		}
		return IcsFetchResult.fetched(calendar, validator(response.header("ETag")),
				validator(response.header("Last-Modified")));
	}

	/**
	 * The body, or null as soon as more than {@link IcsLexer#MAX_BYTES} have come in.
	 * With gzip the packed and the unpacked bytes are both counted, so neither a long
	 * download nor a short one that unpacks into something large gets past the cap.
	 */
	private static byte[] readCapped(BufferedSource wire, boolean gzip) throws IOException {
		CountingSource packed = new CountingSource(wire);
		BufferedSource source = Okio.buffer(gzip ? new GzipSource(packed) : packed);
		Buffer buffer = new Buffer();
		while (source.read(buffer, 8192) != -1) {
			if (buffer.size() > IcsLexer.MAX_BYTES || packed.count > IcsLexer.MAX_BYTES) {
				return null;
			}
		}
		return buffer.readByteArray();
	}

	/** Every gzip stream starts with 1f 8b; a body labelled gzip that does not was never packed. */
	private static boolean looksLikeGzip(BufferedSource source) throws IOException {
		return source.request(2) && source.getBuffer().getByte(0) == (byte) 0x1f
				&& source.getBuffer().getByte(1) == (byte) 0x8b;
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
		String host = withoutTrailingDot(url.host());
		if (ADDRESS_LITERAL.matcher(host).matches() || !hostAllowed(host)) {
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
	 */
	private static List<String> hostPatterns(List<String> entries) {
		List<String> patterns = new ArrayList<>();
		for (String entry : entries == null ? List.<String>of() : entries) {
			if (entry == null || entry.isBlank()) {
				continue;
			}
			String value = entry.strip();
			boolean wildcard = value.startsWith("*.");
			HttpUrl parsed = HttpUrl.parse("https://" + (wildcard ? value.substring(2) : value) + "/");
			if (parsed == null) {
				log.warn("Ignoring an entry of hinata.ics host lists that is not a host name");
				continue;
			}
			String host = withoutTrailingDot(parsed.host());
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

	/**
	 * OkHttp's only way from a host name to a socket: one lookup, every answer
	 * checked, exactly the checked addresses returned. The wait is bounded by the
	 * fetch's timeout, because the call timeout cannot interrupt a JDK lookup.
	 */
	private List<InetAddress> lookup(String host) throws UnknownHostException {
		Future<InetAddress[]> answer;
		try {
			answer = lookups.submit(() -> resolver.resolve(host));
		}
		catch (RejectedExecutionException ex) {
			throw new SlowLookup();
		}
		InetAddress[] addresses;
		try {
			addresses = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			answer.cancel(true);
			throw new SlowLookup();
		}
		catch (InterruptedException ex) {
			answer.cancel(true);
			Thread.currentThread().interrupt();
			throw new SlowLookup();
		}
		catch (ExecutionException ex) {
			throw new UnknownHostException();
		}
		if (addresses == null || addresses.length == 0) {
			throw new UnknownHostException();
		}
		for (InetAddress address : addresses) {
			if (!PublicAddresses.isPublic(address)) {
				throw new UnknownHostException();
			}
		}
		return List.of(addresses);
	}

	static ThreadPoolExecutor pool(String name, int threads, int queue) {
		AtomicInteger count = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queue), runnable -> {
					Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				});
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	/** How host names become addresses: the JDK's resolver, outside tests. */
	@FunctionalInterface
	interface Resolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
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

	/** The lookup did not answer within the fetch's time. */
	private static final class SlowLookup extends UnknownHostException {
		@Serial
		private static final long serialVersionUID = 1L;
	}

	/** Counts what passes through, so the cap also sees the bytes as they came off the wire. */
	private static final class CountingSource extends ForwardingSource {

		private long count;

		CountingSource(Source delegate) {
			super(delegate);
		}

		@Override
		public long read(Buffer sink, long byteCount) throws IOException {
			long read = super.read(sink, byteCount);
			if (read > 0) {
				count += read;
			}
			return read;
		}
	}
}
