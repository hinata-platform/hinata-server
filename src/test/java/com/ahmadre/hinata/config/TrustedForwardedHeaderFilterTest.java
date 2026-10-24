package com.ahmadre.hinata.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client's address comes from {@code X-Forwarded-For} only behind a trusted proxy, and then
 * from the closest hop that is not one: every value left of it is whatever the client wrote. A
 * client that sends the header itself keeps its own address (HIN-175, before limits per address).
 */
class TrustedForwardedHeaderFilterTest {

	/** What the application sees of one request after the filter: address, scheme, host. */
	private record Seen(String address, String scheme, String host, String forwardedFor) {
	}

	private static Seen through(List<String> trusted, String peer, String... headers) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
		request.setRemoteAddr(peer);
		for (int i = 0; i < headers.length; i += 2) {
			request.addHeader(headers[i], headers[i + 1]);
		}
		AtomicReference<Seen> seen = new AtomicReference<>();
		ClientIpResolver resolver = new ClientIpResolver();
		new TrustedForwardedHeaderFilter(trusted).doFilter(request, new MockHttpServletResponse(),
				new MockFilterChain(new HttpServlet() {
					@Override
					protected void service(HttpServletRequest req, HttpServletResponse res) {
						seen.set(new Seen(resolver.resolve(req), req.getScheme(), req.getServerName(),
								req.getHeader("X-Forwarded-For")));
					}
				}));
		return seen.get();
	}

	@Test
	void aClientThatSendsTheHeaderItselfKeepsItsOwnAddress() throws Exception {
		assertThat(through(List.of(), "203.0.113.9", "X-Forwarded-For", "6.6.6.6").address())
				.isEqualTo("203.0.113.9");
		assertThat(through(List.of("10.0.0.0/8"), "203.0.113.9", "X-Forwarded-For", "6.6.6.6").address())
				.isEqualTo("203.0.113.9");
		assertThat(through(List.of(), "203.0.113.9", "Forwarded", "for=6.6.6.6;proto=https").address())
				.isEqualTo("203.0.113.9");
	}

	/** The proxy appends the address it saw; what the client wrote before it is no proof of anything. */
	@Test
	void behindATrustedProxyTheClosestUntrustedHopCounts() throws Exception {
		assertThat(through(List.of("10.0.0.0/8"), "10.0.0.1", "X-Forwarded-For", "6.6.6.6, 198.51.100.7").address())
				.isEqualTo("198.51.100.7");
		assertThat(through(List.of("10.0.0.0/8"), "10.0.0.1", "X-Forwarded-For", "198.51.100.7, 10.0.0.2").address())
				.isEqualTo("198.51.100.7");
		// Only trusted hops: the request came from inside, the proxy is the address.
		assertThat(through(List.of("10.0.0.0/8"), "10.0.0.1", "X-Forwarded-For", "10.0.0.5").address())
				.isEqualTo("10.0.0.1");
		assertThat(through(List.of("10.0.0.0/8"), "10.0.0.1", "X-Forwarded-For", "not an address").address())
				.isEqualTo("not an address");
	}

	@Test
	void schemeAndHostPassOnAsBefore() throws Exception {
		Seen seen = through(List.of(), "203.0.113.9", "X-Forwarded-Proto", "https", "X-Forwarded-Host",
				"track.example.org", "X-Forwarded-For", "6.6.6.6", "Forwarded", "for=6.6.6.6");
		assertThat(seen.scheme()).isEqualTo("https");
		assertThat(seen.host()).isEqualTo("track.example.org");
		assertThat(seen.address()).isEqualTo("203.0.113.9");
		assertThat(seen.forwardedFor()).as("used up, not passed on").isNull();
	}

	@Test
	void anIgnoredHeaderIsRemembered() throws Exception {
		TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter(List.of());
		assertThat(filter.ignoredAt()).isNull();
		MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/api/v1/meta");
		filter.doFilter(direct, new MockHttpServletResponse(), new MockFilterChain());
		assertThat(filter.ignoredAt()).as("no header, nothing ignored").isNull();

		MockHttpServletRequest proxied = new MockHttpServletRequest("GET", "/api/v1/meta");
		proxied.addHeader("X-Forwarded-For", "198.51.100.7");
		filter.doFilter(proxied, new MockHttpServletResponse(), new MockFilterChain());
		assertThat(filter.ignoredAt()).isNotNull();
	}

	/**
	 * Removing {@code for=} by text joined what stood on either side of it into a new one: a
	 * client wrote its own address into what Spring then took as the client's.
	 */
	@Test
	void noForwardedHeaderAssemblesAnAddress() throws Exception {
		for (String forged : List.of("ffor=;or=203.0.113.7", "fofor=x;r=203.0.113.7", "forfor=;=203.0.113.7",
				"for=\"6.6.6.6\";for=203.0.113.7")) {
			assertThat(through(List.of(), "198.51.100.9", "Forwarded", forged).address()).as(forged)
					.isEqualTo("198.51.100.9");
			assertThat(through(List.of("10.0.0.0/8"), "10.0.0.1", "Forwarded", forged).address()).as(forged)
					.isEqualTo("10.0.0.1");
		}
	}

	@Test
	void aTrustedProxyKeepsProtoAndHostOfForwarded() throws Exception {
		Seen seen = through(List.of("10.0.0.0/8"), "10.0.0.1", "Forwarded",
				"for=6.6.6.6;proto=https;host=track.example.org", "X-Forwarded-For", "198.51.100.7");
		assertThat(seen.scheme()).isEqualTo("https");
		assertThat(seen.host()).isEqualTo("track.example.org");
		assertThat(seen.address()).isEqualTo("198.51.100.7");
	}

	@Test
	void forwardedKeepsProtoAndHostAndNothingElse() {
		assertThat(TrustedForwardedHeaderFilter.proxyFacts("for=6.6.6.6;proto=https;host=a.example"))
				.isEqualTo("proto=https;host=a.example");
		assertThat(TrustedForwardedHeaderFilter.proxyFacts("proto=https;for=\"[2001:db8::1]:443\"")).isEqualTo("proto=https");
		assertThat(TrustedForwardedHeaderFilter.proxyFacts("for=1.1.1.1, for=2.2.2.2")).isNull();
		assertThat(TrustedForwardedHeaderFilter.proxyFacts("by=10.0.0.1;host=\"a;b.example\"")).isEqualTo("host=\"a;b.example\"");
		assertThat(TrustedForwardedHeaderFilter.proxyFacts("ffor=;or=203.0.113.7")).isNull();
	}
}
