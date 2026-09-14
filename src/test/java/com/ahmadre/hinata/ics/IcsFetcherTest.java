package com.ahmadre.hinata.ics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.ahmadre.hinata.config.HinataProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static com.ahmadre.hinata.ics.IcsFetchError.BUSY;
import static com.ahmadre.hinata.ics.IcsFetchError.CREDENTIALS_IN_URL;
import static com.ahmadre.hinata.ics.IcsFetchError.ENCODING;
import static com.ahmadre.hinata.ics.IcsFetchError.HOST_NOT_ALLOWED;
import static com.ahmadre.hinata.ics.IcsFetchError.HTTP_STATUS;
import static com.ahmadre.hinata.ics.IcsFetchError.NOT_A_CALENDAR;
import static com.ahmadre.hinata.ics.IcsFetchError.PORT_NOT_ALLOWED;
import static com.ahmadre.hinata.ics.IcsFetchError.REDIRECT;
import static com.ahmadre.hinata.ics.IcsFetchError.TIMEOUT;
import static com.ahmadre.hinata.ics.IcsFetchError.TLS_FAILED;
import static com.ahmadre.hinata.ics.IcsFetchError.TOO_LARGE;
import static com.ahmadre.hinata.ics.IcsFetchError.URL_INVALID;
import static com.ahmadre.hinata.ics.IcsFetchResult.Outcome.FETCHED;
import static com.ahmadre.hinata.ics.IcsFetchResult.Outcome.NOT_MODIFIED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fetcher against a real HTTPS server on this machine.
 *
 * <p>Production refuses exactly what a local test server is: a loopback address on
 * a random port. None of those rules is relaxed here. Instead the fetcher is told
 * that {@code calendar.test} lives at a public address, and the socket it opens to
 * that address is connected to the local server underneath. Which address the
 * fetcher asked for is recorded, and the certificate is issued for
 * {@code calendar.test} by a throwaway authority only this fetcher trusts.
 */
class IcsFetcherTest {

	private static final String HOST = "calendar.test";
	private static final String FEED = "https://" + HOST + "/feed.ics";
	private static final String TOKEN = "private-5ecr3t70k3n";
	private static final byte[] CALENDAR =
			"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.US_ASCII);

	private static final InetAddress PUBLIC = literal("93.184.215.14");
	private static final InetAddress PRIVATE = literal("10.0.0.7");
	private static final InetAddress LOOPBACK = literal("127.0.0.1");
	private static final InetAddress METADATA = literal("169.254.169.254");

	private static final HeldCertificate AUTHORITY = new HeldCertificate.Builder().certificateAuthority(0).build();

	private final List<IcsFetcher> fetchers = new ArrayList<>();
	private MockWebServer server;
	private Remap sockets;
	private Answers answers;
	private HinataProperties.Ics config;

	@BeforeEach
	void startServer() throws IOException {
		server = new MockWebServer();
		server.useHttps(serverCertificate(HOST), false);
		server.start();
		sockets = new Remap(new InetSocketAddress(InetAddress.getByName(server.getHostName()), server.getPort()));
		answers = new Answers(new InetAddress[] { PUBLIC });
		config = new HinataProperties.Ics();
	}

	@AfterEach
	void stopServer() throws IOException {
		fetchers.forEach(IcsFetcher::destroy);
		server.shutdown();
	}

	@Test
	void fetchesACalendarAndAsksForItPlainly() throws InterruptedException {
		server.enqueue(calendar()
				.setHeader("ETag", "\"v1\"")
				.setHeader("Last-Modified", "Mon, 14 Sep 2026 08:00:00 GMT"));

		IcsFetchResult result = fetch(FEED + "?token=" + TOKEN);

		assertThat(result.outcome()).isEqualTo(FETCHED);
		assertThat(result.body()).isEqualTo(CALENDAR);
		assertThat(result.etag()).isEqualTo("\"v1\"");
		assertThat(result.lastModified()).isEqualTo("Mon, 14 Sep 2026 08:00:00 GMT");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getPath()).isEqualTo("/feed.ics?token=" + TOKEN);
		assertThat(request.getHeader("User-Agent")).isEqualTo("hinata");
		assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity");
	}

	@Test
	void connectsToTheAddressItCheckedAndAsksOnlyOnce() {
		// DNS rebinding: a public answer for the check, a loopback one for the
		// connection. There is no second question for it to answer.
		answers = new Answers(new InetAddress[] { PUBLIC }, new InetAddress[] { LOOPBACK });
		server.enqueue(calendar());

		IcsFetchResult result = fetch(FEED);

		assertThat(result.outcome()).isEqualTo(FETCHED);
		assertThat(answers.calls()).isEqualTo(1);
		assertThat(sockets.requested).containsExactly(new InetSocketAddress(PUBLIC, 443));
	}

	@Test
	void refusesAHostWithAnyAddressOffThePublicInternetBeforeConnecting() {
		for (InetAddress[] answer : List.of(new InetAddress[] { PRIVATE },
				new InetAddress[] { PUBLIC, PRIVATE }, new InetAddress[] { METADATA })) {
			answers = new Answers(answer);

			assertThat(fetch(FEED).error()).isEqualTo(HOST_NOT_ALLOWED);
		}
		assertThat(sockets.requested).isEmpty();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void refusesAnAddressLiteralWithoutResolvingIt() {
		// OkHttp connects to anything that looks like an address without asking
		// the Dns hook, so none of these may ever reach OkHttp.
		for (String url : List.of("https://127.0.0.1/feed.ics", "https://127.1/feed.ics",
				"https://2130706433/feed.ics", "https://[::1]/feed.ics",
				"https://[::ffff:a9fe:a9fe]/feed.ics", "https://93.184.215.14/feed.ics")) {
			assertThat(fetch(url).error()).as(url).isEqualTo(HOST_NOT_ALLOWED);
		}
		assertThat(answers.calls()).isZero();
		assertThat(sockets.requested).isEmpty();
	}

	@Test
	void speaksHttpsOnlyOnTheAgreedPortsAndReadsWebcalAsHttps() {
		assertThat(fetch("http://" + HOST + "/feed.ics").error()).isEqualTo(URL_INVALID);
		assertThat(fetch("ftp://" + HOST + "/feed.ics").error()).isEqualTo(URL_INVALID);
		assertThat(fetch("not an address").error()).isEqualTo(URL_INVALID);
		assertThat(fetch("https://" + HOST + ":8080/feed.ics").error()).isEqualTo(PORT_NOT_ALLOWED);
		assertThat(fetch("https://" + HOST + ":80/feed.ics").error()).isEqualTo(PORT_NOT_ALLOWED);

		server.enqueue(calendar());
		server.enqueue(calendar());
		assertThat(fetch("webcal://" + HOST + "/feed.ics").outcome()).isEqualTo(FETCHED);
		assertThat(fetch("https://" + HOST + ":8443/feed.ics").outcome()).isEqualTo(FETCHED);
		assertThat(sockets.requested)
				.containsExactly(new InetSocketAddress(PUBLIC, 443), new InetSocketAddress(PUBLIC, 8443));
	}

	@Test
	void refusesCredentialsInTheAddress() {
		assertThat(fetch("https://ada:" + TOKEN + "@" + HOST + "/feed.ics").error()).isEqualTo(CREDENTIALS_IN_URL);
		// Which part is the host? Refused before anyone has to decide.
		assertThat(fetch("https://" + HOST + "@evil.test/feed.ics").error()).isEqualTo(CREDENTIALS_IN_URL);
		assertThat(answers.calls()).isZero();
	}

	@Test
	void followsNoRedirect() {
		int[] statuses = { 301, 302, 303, 307, 308 };
		for (int status : statuses) {
			server.enqueue(new MockResponse().setResponseCode(status)
					.setHeader("Location", "https://169.254.169.254/latest/meta-data/"));

			IcsFetchResult result = fetch(FEED);

			assertThat(result.error()).isEqualTo(REDIRECT);
			assertThat(result.httpStatus()).isEqualTo(status);
		}
		assertThat(server.getRequestCount()).isEqualTo(statuses.length);
	}

	@Test
	void stopsAtTwoMegabytesWhetherTheSizeIsDeclaredOrNot() {
		byte[] threeMegabytes = new byte[3 * 1024 * 1024];
		Arrays.fill(threeMegabytes, (byte) 'A');
		// Chunked, so only the reading itself can notice...
		server.enqueue(calendar().setChunkedBody(new Buffer().write(threeMegabytes), 64 * 1024));
		// ...and with an honest length, which is refused before reading.
		server.enqueue(calendar().setBody(new Buffer().write(threeMegabytes)));

		assertThat(fetch(FEED).error()).isEqualTo(TOO_LARGE);
		assertThat(fetch(FEED).error()).isEqualTo(TOO_LARGE);
	}

	@Test
	void givesUpOnAServerThatTrickles() {
		server.enqueue(calendar().throttleBody(1, 1, TimeUnit.SECONDS));
		IcsFetcher impatient = fetcher(answers, Duration.ofSeconds(1), IcsFetcher.pool("test", 1, 1));
		long started = System.nanoTime();

		IcsFetchResult result = impatient.fetch(FEED, null, null).join();

		assertThat(result.error()).isEqualTo(TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void givesUpOnALookupThatNeverAnswers() {
		CountDownLatch never = new CountDownLatch(1);
		IcsFetcher.Resolver hanging = host -> {
			await(never);
			return new InetAddress[] { PUBLIC };
		};
		IcsFetcher impatient = fetcher(hanging, Duration.ofSeconds(1), IcsFetcher.pool("test", 1, 1));
		long started = System.nanoTime();

		IcsFetchResult result = impatient.fetch(FEED, null, null).join();

		assertThat(result.error()).isEqualTo(TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
		never.countDown();
	}

	@Test
	void takesWhatIsLabelledOrNamedAsACalendarAndNothingElse() {
		server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"));
		server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody(new Buffer().write(CALENDAR)));

		assertThat(fetch("https://" + HOST + "/calendar").error()).isEqualTo(NOT_A_CALENDAR);
		// A server that labels every file text/plain still names this one .ics.
		assertThat(fetch(FEED).outcome()).isEqualTo(FETCHED);
	}

	@Test
	void unpacksGzipBecauseICloudSendsItAnywayAndRefusesAnyOtherEncoding() throws IOException {
		// iCloud answers Accept-Encoding: identity with a gzip body (seen 2026-09-14).
		server.enqueue(calendar().setBody(gzip(CALENDAR)).setHeader("Content-Encoding", "gzip"));
		server.enqueue(calendar().setHeader("Content-Encoding", "br"));

		IcsFetchResult unpacked = fetch(FEED);

		assertThat(unpacked.outcome()).isEqualTo(FETCHED);
		assertThat(unpacked.body()).isEqualTo(CALENDAR);
		assertThat(fetch(FEED).error()).isEqualTo(ENCODING);
	}

	@Test
	void stopsUnpackingAtTwoMegabytes() throws IOException {
		byte[] threeMegabytes = new byte[3 * 1024 * 1024];
		Arrays.fill(threeMegabytes, (byte) 'A');
		Buffer bomb = gzip(threeMegabytes);
		assertThat(bomb.size()).isLessThan(64 * 1024);
		server.enqueue(calendar().setBody(bomb).setHeader("Content-Encoding", "gzip"));

		assertThat(fetch(FEED).error()).isEqualTo(TOO_LARGE);
	}

	@Test
	void refusesABodyLabelledGzipThatIsNot() {
		server.enqueue(calendar().setHeader("Content-Encoding", "gzip"));

		assertThat(fetch(FEED).error()).isEqualTo(ENCODING);
	}

	@Test
	void sendsTheValidatorsBackAndReportsAnUnchangedCalendar() throws InterruptedException {
		server.enqueue(new MockResponse().setResponseCode(304));

		IcsFetchResult result = fetcher().fetch(FEED, "\"v1\"", "Mon, 14 Sep 2026 08:00:00 GMT").join();

		assertThat(result.outcome()).isEqualTo(NOT_MODIFIED);
		assertThat(result.etag()).isEqualTo("\"v1\"");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getHeader("If-None-Match")).isEqualTo("\"v1\"");
		assertThat(request.getHeader("If-Modified-Since")).isEqualTo("Mon, 14 Sep 2026 08:00:00 GMT");
	}

	@Test
	void reportsAnErrorStatusWithItsCode() {
		server.enqueue(new MockResponse().setResponseCode(404));

		IcsFetchResult result = fetch(FEED);

		assertThat(result.error()).isEqualTo(HTTP_STATUS);
		assertThat(result.httpStatus()).isEqualTo(404);
	}

	@Test
	void refusesACertificateIssuedForAnotherHost() {
		server.useHttps(serverCertificate("other.test"), false);
		server.enqueue(calendar());

		assertThat(fetch(FEED).error()).isEqualTo(TLS_FAILED);
	}

	@Test
	void honoursTheHostListsButNeverOpensAPrivateAddress() {
		config.setDeniedHosts(List.of("CALENDAR.test."));
		assertThat(fetch(FEED).error()).isEqualTo(HOST_NOT_ALLOWED);

		config.setDeniedHosts(List.of());
		config.setAllowedHosts(List.of("*.google.com"));
		assertThat(fetch(FEED).error()).isEqualTo(HOST_NOT_ALLOWED);

		config.setAllowedHosts(List.of("*.test"));
		answers = new Answers(new InetAddress[] { PRIVATE });
		assertThat(fetch(FEED).error()).isEqualTo(HOST_NOT_ALLOWED);

		answers = new Answers(new InetAddress[] { PUBLIC });
		server.enqueue(calendar());
		assertThat(fetch(FEED).outcome()).isEqualTo(FETCHED);
	}

	@Test
	void checksAnAddressWithoutResolvingIt() {
		assertThat(fetcher().check(FEED + "?token=" + TOKEN)).isEmpty();
		assertThat(fetcher().check("webcal://" + HOST + "/feed.ics")).isEmpty();
		assertThat(fetcher().check("http://" + HOST + "/feed.ics")).contains(URL_INVALID);
		assertThat(answers.calls()).isZero();
	}

	@Test
	void answersBusyAtOnceWhenTheQueueIsFull() {
		CountDownLatch release = new CountDownLatch(1);
		ThreadPoolExecutor full = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
		full.execute(() -> await(release));
		full.execute(() -> { });

		IcsFetchResult result = fetcher(answers, IcsFetcher.TIMEOUT, full).fetch(FEED, null, null).join();

		assertThat(result.error()).isEqualTo(BUSY);
		release.countDown();
	}

	@Test
	void neverWritesTheAddressIntoALogOrAResult() {
		String secretUrl = FEED + "?token=" + TOKEN;
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/?token=" + TOKEN));
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(calendar().throttleBody(1, 1, TimeUnit.SECONDS));
		List<IcsFetchResult> results = new ArrayList<>();

		List<String> lines = captureEveryLog(() -> {
			results.add(fetch(secretUrl));
			results.add(fetch(secretUrl));
			results.add(fetcher(answers, Duration.ofMillis(500), IcsFetcher.pool("test", 1, 1))
					.fetch(secretUrl, null, null).join());
			answers = new Answers(new InetAddress[] { PRIVATE });
			results.add(fetch(secretUrl));
			results.add(fetch("https://ada:" + TOKEN + "@" + HOST + "/feed.ics"));
			results.add(fetch("http://" + HOST + "/feed.ics?token=" + TOKEN));
		});

		assertThat(results).extracting(IcsFetchResult::error).doesNotContainNull();
		assertThat(results).allSatisfy(result -> assertThat(result.toString()).doesNotContain(TOKEN).doesNotContain(HOST));
		assertThat(lines).noneMatch(line -> line.contains(TOKEN) || line.contains(HOST));
	}

	// --- helpers --------------------------------------------------------------------

	private IcsFetchResult fetch(String url) {
		return fetcher().fetch(url, null, null).join();
	}

	private IcsFetcher fetcher() {
		return fetcher(answers, IcsFetcher.TIMEOUT, IcsFetcher.pool("test", 2, 4));
	}

	private IcsFetcher fetcher(IcsFetcher.Resolver resolver, Duration timeout, ExecutorService executor) {
		HandshakeCertificates trust = new HandshakeCertificates.Builder()
				.addTrustedCertificate(AUTHORITY.certificate())
				.build();
		IcsFetcher fetcher = new IcsFetcher(config, resolver,
				new IcsFetcher.Transport(sockets, trust.sslSocketFactory(), trust.trustManager()), timeout, executor);
		fetchers.add(fetcher);
		return fetcher;
	}

	private static MockResponse calendar() {
		return new MockResponse()
				.setHeader("Content-Type", "text/calendar; charset=utf-8")
				.setBody(new Buffer().write(CALENDAR));
	}

	private static Buffer gzip(byte[] bytes) throws IOException {
		Buffer packed = new Buffer();
		try (BufferedSink sink = Okio.buffer(new GzipSink(packed))) {
			sink.write(bytes);
		}
		return packed;
	}

	private static javax.net.ssl.SSLSocketFactory serverCertificate(String host) {
		HeldCertificate certificate = new HeldCertificate.Builder()
				.addSubjectAlternativeName(host)
				.signedBy(AUTHORITY)
				.build();
		return new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory();
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Everything logged while [action] runs, through Logback and through
	 * java.util.logging (where OkHttp writes), at every level. The test server's
	 * own request log is left out: it prints the request line it received.
	 */
	private static List<String> captureEveryLog(Runnable action) {
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Level rootLevel = root.getLevel();
		java.util.logging.Logger julRoot = java.util.logging.Logger.getLogger("");
		java.util.logging.Level julLevel = julRoot.getLevel();
		List<String> julLines = new CopyOnWriteArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord entry) {
				String name = String.valueOf(entry.getLoggerName());
				if (!name.startsWith("okhttp3.mockwebserver")) {
					julLines.add(name + " " + entry.getMessage() + " " + entry.getThrown());
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		appender.start();
		root.addAppender(appender);
		root.setLevel(Level.TRACE);
		julRoot.addHandler(handler);
		julRoot.setLevel(java.util.logging.Level.ALL);
		try {
			action.run();
		}
		finally {
			root.setLevel(rootLevel);
			root.detachAppender(appender);
			appender.stop();
			julRoot.removeHandler(handler);
			julRoot.setLevel(julLevel);
		}
		Stream<String> logback = appender.list.stream().map(event -> {
			IThrowableProxy thrown = event.getThrowableProxy();
			return event.getLoggerName() + " " + event.getFormattedMessage()
					+ (thrown == null ? "" : " " + ThrowableProxyUtil.asString(thrown));
		});
		return Stream.concat(logback, julLines.stream()).toList();
	}

	/** Connects every socket to the local server, and notes the address the fetcher asked for. */
	private static final class Remap extends SocketFactory {

		private final InetSocketAddress server;
		private final List<InetSocketAddress> requested = new CopyOnWriteArrayList<>();

		Remap(InetSocketAddress server) {
			this.server = server;
		}

		@Override
		public Socket createSocket() {
			return new Socket() {
				@Override
				public void connect(SocketAddress endpoint, int timeout) throws IOException {
					requested.add((InetSocketAddress) endpoint);
					super.connect(server, timeout);
				}
			};
		}

		@Override
		public Socket createSocket(String host, int port) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(InetAddress host, int port) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
			throw new UnsupportedOperationException();
		}
	}

	/** Prepared answers in order, the last one repeated, and a count of the questions. */
	private static final class Answers implements IcsFetcher.Resolver {

		private final Deque<InetAddress[]> queue = new ArrayDeque<>();
		private final AtomicInteger calls = new AtomicInteger();

		Answers(InetAddress[]... answers) {
			queue.addAll(List.of(answers));
		}

		@Override
		public synchronized InetAddress[] resolve(String host) {
			calls.incrementAndGet();
			return queue.size() > 1 ? queue.poll() : queue.peek();
		}

		int calls() {
			return calls.get();
		}
	}
}
