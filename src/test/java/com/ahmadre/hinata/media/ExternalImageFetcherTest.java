package com.ahmadre.hinata.media;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.PreparedAnswers;
import com.ahmadre.hinata.common.RemappedSockets;
import com.ahmadre.hinata.storage.StorageService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The image proxy against a plain HTTP server on this machine.
 *
 * <p>As in {@code IcsFetcherTest}, no rule is relaxed for the local server: the
 * fetcher is told that {@code images.test} lives at a public address, and the socket
 * it opens to that address is connected to the local server underneath. Which address
 * the fetcher asked for is recorded.
 */
class ExternalImageFetcherTest {

	private static final String HOST = "images.test";
	private static final String LOGO = "http://" + HOST + "/logo.png";
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

	private static final InetAddress PUBLIC = literal("93.184.215.14");
	private static final InetAddress PRIVATE = literal("10.0.0.7");
	private static final InetAddress LOOPBACK = literal("127.0.0.1");

	private final List<ExternalImageFetcher> fetchers = new ArrayList<>();
	private MockWebServer server;
	private RemappedSockets sockets;
	private PreparedAnswers answers;

	@BeforeEach
	void startServer() throws IOException {
		server = new MockWebServer();
		server.start();
		sockets = new RemappedSockets(new InetSocketAddress(InetAddress.getByName(server.getHostName()), server.getPort()));
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC });
	}

	@AfterEach
	void stopServer() throws IOException {
		fetchers.forEach(ExternalImageFetcher::destroy);
		server.shutdown();
	}

	@Test
	void connectsToTheAddressItCheckedAndAsksOnlyOnce() throws InterruptedException {
		// DNS rebinding: a public answer for the check, a loopback one for the
		// connection. There is no second question for it to answer.
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC }, new InetAddress[] { LOOPBACK });
		server.enqueue(image());

		StorageService.StoredObject fetched = fetch(LOGO);

		assertThat(fetched.data()).isEqualTo(PNG);
		assertThat(fetched.contentType()).isEqualTo("image/png");
		assertThat(answers.calls()).isEqualTo(1);
		assertThat(sockets.requested()).containsExactly(new InetSocketAddress(PUBLIC, 80));
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://127.0.0.1/logo.png",
			"http://169.254.169.254/latest/meta-data/",
			"http://[::ffff:169.254.169.254]/logo.png",
			"http://[fd00::1]/logo.png",
			// The NAT64 spelling of the metadata address. The JDK's own address
			// predicates see an ordinary IPv6 address here, and so did this class
			// until it asked PublicAddresses.
			"http://[64:ff9b::a9fe:a9fe]/logo.png",
			// Spellings OkHttp connects to without a lookup, whatever they point at.
			"http://127.1/logo.png",
			"http://2130706433/logo.png",
			"http://[::ffff:a9fe:a9fe]/logo.png",
			"http://./logo.png" })
	void refusesAnAddressOffThePublicInternetWithoutResolvingIt(String url) {
		assertRefused(() -> fetch(url), "error.media.urlNotAllowed");
		assertThat(answers.calls()).isZero();
		assertThat(sockets.requested()).isEmpty();
	}

	@Test
	void takesAPublicAddressWrittenPlainlyWithoutALookup() {
		server.enqueue(image());

		assertThat(fetch("http://93.184.215.14/logo.png").data()).isEqualTo(PNG);
		assertThat(answers.calls()).isZero();
		assertThat(sockets.requested()).containsExactly(new InetSocketAddress(PUBLIC, 80));
	}

	@Test
	void checksARedirectLikeTheFirstRequest() {
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://inner.test/logo.png"));
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC }, new InetAddress[] { PRIVATE });

		assertRefused(() -> fetch(LOGO), "error.media.urlNotAllowed");
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void followsAtMostThreeRedirectsAndLooksEachOneUpAgain() {
		for (int i = 0; i < 4; i++) {
			server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/logo.png"));
		}

		assertRefused(() -> fetch(LOGO), "error.media.fetchFailed");
		assertThat(server.getRequestCount()).isEqualTo(4);
		// Nothing pooled: a kept connection would have skipped the lookup.
		assertThat(answers.calls()).isEqualTo(4);
	}

	@Test
	void stopsAtTenMegabytes() {
		server.enqueue(image().setChunkedBody(new Buffer().write(new byte[11 * 1024 * 1024]), 64 * 1024));

		assertRefused(() -> fetch(LOGO), "error.media.tooLarge");
	}

	@Test
	void givesUpOnABodyThatTrickles() {
		server.enqueue(image().throttleBody(1, 1, TimeUnit.SECONDS));
		ExternalImageFetcher impatient = fetcher(Duration.ofSeconds(1));
		long started = System.nanoTime();

		assertRefused(() -> impatient.fetch(LOGO), "error.media.fetchFailed");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}

	// --- helpers --------------------------------------------------------------------

	private StorageService.StoredObject fetch(String url) {
		return fetcher(ExternalImageFetcher.TIMEOUT).fetch(url);
	}

	private ExternalImageFetcher fetcher(Duration timeout) {
		ExternalImageFetcher fetcher = new ExternalImageFetcher(answers, sockets, timeout);
		fetchers.add(fetcher);
		return fetcher;
	}

	private static void assertRefused(ThrowingCallable fetch, String messageKey) {
		assertThatThrownBy(fetch).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo(messageKey));
	}

	private static MockResponse image() {
		return new MockResponse().setHeader("Content-Type", "image/png").setBody(new Buffer().write(PNG));
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
