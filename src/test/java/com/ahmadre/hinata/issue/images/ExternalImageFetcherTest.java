package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.PreparedAnswers;
import com.ahmadre.hinata.common.RemappedSockets;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mail image fetcher against a plain HTTP server on this machine (HIN-181). As in
 * {@code LogoFetcherTest}, no rule is relaxed for the local server: the fetcher is told
 * {@code mail.test} lives at a public address, and the socket it opens to that address is
 * connected to the local server underneath.
 */
class ExternalImageFetcherTest {

	private static final String HOST = "mail.test";
	private static final String IMAGE = "http://" + HOST + "/header.png";
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13 };
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16 };
	private static final byte[] GIF = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] WEBP = "RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);

	private static final InetAddress PUBLIC = literal("93.184.215.14");
	private static final InetAddress PRIVATE = literal("10.0.0.7");
	private static final InetAddress LOOPBACK = literal("127.0.0.1");
	private static final InetAddress METADATA = literal("169.254.169.254");
	private static final InetAddress UNIQUE_LOCAL = literal("fd00::1");

	private MockWebServer server;
	private RemappedSockets sockets;
	private PreparedAnswers answers;
	private ExternalImageFetcher fetcher;

	@BeforeEach
	void startServer() throws IOException {
		server = new MockWebServer();
		server.start();
		sockets = new RemappedSockets(new InetSocketAddress(InetAddress.getByName(server.getHostName()), server.getPort()));
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC });
	}

	@AfterEach
	void stopServer() throws IOException {
		if (fetcher != null) fetcher.destroy();
		server.shutdown();
	}

	private ExternalImageFetcher.Image fetch(String url) {
		if (fetcher == null) fetcher = new ExternalImageFetcher(answers, sockets, Duration.ofSeconds(5));
		return fetcher.fetch(url);
	}

	@Test
	void fetch_givesTheImageAndSaysWhatItIsByItsBytes() {
		server.enqueue(body(PNG, "application/octet-stream"));

		ExternalImageFetcher.Image image = fetch(IMAGE);

		assertThat(image.data()).isEqualTo(PNG);
		assertThat(image.contentType()).isEqualTo("image/png");
		assertThat(sockets.requested()).containsExactly(new InetSocketAddress(PUBLIC, 80));
	}

	@Test
	void fetch_sendsNoCookieNoReferrerAndAFixedAgent() throws InterruptedException {
		server.enqueue(body(JPEG, "image/jpeg"));

		fetch(IMAGE);

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getHeader("Cookie")).isNull();
		assertThat(request.getHeader("Referer")).isNull();
		assertThat(request.getHeader("User-Agent")).isEqualTo("hinata-image-proxy");
	}

	@Test
	void fetch_takesJpegGifAndWebp() {
		for (byte[] bytes : new byte[][] { JPEG, GIF, WEBP }) server.enqueue(body(bytes, "image/png"));

		assertThat(fetch(IMAGE).contentType()).isEqualTo("image/jpeg");
		assertThat(fetch(IMAGE).contentType()).isEqualTo("image/gif");
		assertThat(fetch(IMAGE).contentType()).isEqualTo("image/webp");
	}

	@Test
	void fetch_refusesSvgAndHtmlWhateverTheServerCallsThem() {
		server.enqueue(body("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8), "image/png"));
		server.enqueue(body("<html>tracking</html>".getBytes(StandardCharsets.UTF_8), "image/svg+xml"));

		assertRefused(() -> fetch(IMAGE), "error.media.notAnImage");
		assertRefused(() -> fetch(IMAGE), "error.media.notAnImage");
	}

	@Test
	void fetch_asksTheNameOnceAndConnectsToWhatItChecked() {
		// DNS rebinding: a public answer for the check, a loopback one for the connection.
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC }, new InetAddress[] { LOOPBACK });
		server.enqueue(body(PNG, "image/png"));

		fetch(IMAGE);

		assertThat(answers.calls()).isEqualTo(1);
		assertThat(sockets.requested()).containsExactly(new InetSocketAddress(PUBLIC, 80));
	}

	@Test
	void fetch_refusesANameWithAnyAnswerOffThePublicInternet() {
		for (InetAddress inside : new InetAddress[] { PRIVATE, LOOPBACK, METADATA, UNIQUE_LOCAL }) {
			answers = new PreparedAnswers(new InetAddress[] { PUBLIC, inside });
			if (fetcher != null) fetcher.destroy();
			fetcher = null;

			assertRefused(() -> fetch(IMAGE), "error.media.urlNotAllowed");
		}
		assertThat(sockets.requested()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://127.0.0.1/x.png", "http://127.1/x.png", "http://2130706433/x.png",
			"http://[::1]/x.png", "http://[::ffff:a9fe:a9fe]/x.png", "http://169.254.169.254/latest/meta-data",
			"http://10.0.0.7/x.png", "http://[fd00::1]/x.png", "http://mail.test:8080/x.png",
			"https://mail.test:9443/x.png" })
	void fetch_refusesAddressesAndPortsItMayNotAsk(String url) {
		assertRefused(() -> fetch(url), "error.media.urlNotAllowed");
		assertThat(answers.calls()).isZero();
		assertThat(sockets.requested()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://mail.test/x.png", "file:///etc/passwd", "data:image/png;base64,iVBOR", "x" })
	void fetch_refusesWhatIsNoHttpAddress(String url) {
		assertRefused(() -> fetch(url), "error.media.urlInvalid");
	}

	@Test
	void fetch_checksEveryRedirectAgain() {
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC }, new InetAddress[] { PRIVATE });
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://inside.test/x.png"));

		assertRefused(() -> fetch(IMAGE), "error.media.urlNotAllowed");
		assertThat(sockets.requested()).containsExactly(new InetSocketAddress(PUBLIC, 80));
	}

	@Test
	void fetch_refusesARedirectToALiteralInside() {
		server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "http://169.254.169.254/"));

		assertRefused(() -> fetch(IMAGE), "error.media.urlNotAllowed");
	}

	@Test
	void fetch_followsThreeRedirectsAndNoMore() {
		answers = new PreparedAnswers(new InetAddress[] { PUBLIC });
		for (int i = 0; i < 3; i++) {
			server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", IMAGE + "?hop=" + i));
		}
		server.enqueue(body(PNG, "image/png"));
		assertThat(fetch(IMAGE).data()).isEqualTo(PNG);

		for (int i = 0; i < 4; i++) {
			server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", IMAGE + "?again=" + i));
		}
		assertRefused(() -> fetch(IMAGE), "error.media.fetchFailed");
	}

	@Test
	void fetch_readsNoMoreThanEightMegabytes() {
		byte[] large = new byte[ExternalImageFetcher.MAX_BYTES + 1];
		System.arraycopy(PNG, 0, large, 0, PNG.length);
		server.enqueue(body(large, "image/png"));

		assertRefused(() -> fetch(IMAGE), "error.media.tooLarge");
	}

	@Test
	void fetch_givesUpOnAnErrorAnswer() {
		server.enqueue(new MockResponse().setResponseCode(404));

		assertThatThrownBy(() -> fetch(IMAGE)).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getMessageKey()).isEqualTo("error.media.fetchFailed");
			assertThat(ex.getStatus().value()).isEqualTo(502);
		});
	}

	@Test
	void fetch_givesUpOnAnUnknownName() {
		fetcher = new ExternalImageFetcher(host -> {
			throw new UnknownHostException(host);
		}, sockets, Duration.ofSeconds(5));

		assertRefused(() -> fetcher.fetch(IMAGE), "error.media.urlNotAllowed");
	}

	private static MockResponse body(byte[] bytes, String type) {
		return new MockResponse().setHeader("Content-Type", type).setBody(new Buffer().write(bytes));
	}

	private static void assertRefused(ThrowingCallable fetch, String messageKey) {
		assertThatThrownBy(fetch).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo(messageKey));
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		}
		catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}
}
