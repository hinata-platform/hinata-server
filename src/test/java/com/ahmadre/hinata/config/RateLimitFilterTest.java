package com.ahmadre.hinata.config;

import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auth budget is deliberately tiny — it protects credential endpoints. That
 * makes *which* paths draw from it a correctness question: the sign-in screen
 * asks {@code /sso/providers} which buttons to draw on every visit, and when
 * that lookup is metered like a login attempt, a couple of app starts (or
 * several clients behind one proxy IP) answer 429. A client cannot tell that
 * apart from "this server has no SSO", so the buttons just vanish.
 */
class RateLimitFilterTest {

	private static final String LOGIN = "/api/v1/auth/login";
	private static final String SSO_PROVIDERS = "/api/v1/auth/sso/providers";
	private static final String REFRESH = "/api/v1/auth/refresh";

	private HinataProperties properties;
	private RateLimitFilter filter;
	private AtomicInteger passed;
	private FilterChain chain;

	@BeforeEach
	void setUp() {
		properties = new HinataProperties();
		properties.getRateLimit().setAuthPerMinute(3);
		properties.getRateLimit().setRefreshPerMinute(8);
		properties.getRateLimit().setApiPerMinute(10);
		SettingsService settings = Mockito.mock(SettingsService.class);
		Mockito.when(settings.get()).thenReturn(new ServerSettings());
		filter = new RateLimitFilter(properties, new SecurityPolicy(settings, properties),
				new ClientIpResolver(properties), new StaticMessageSource());
		passed = new AtomicInteger();
		chain = (request, response) -> passed.incrementAndGet();
	}

	/** Sends one request from the same client and returns its status. */
	private int call(String uri) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRemoteAddr("203.0.113.7");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		return response.getStatus();
	}

	@Test
	void credentialEndpointsKeepTheStrictBudget() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(call(LOGIN)).isEqualTo(HttpStatus.OK.value());
		}

		assertThat(call(LOGIN)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(passed.get()).isEqualTo(3);
	}

	@Test
	void refreshingATokenIsNotASignInAttempt() throws Exception {
		// Presenting a token this server signed is not a guess at a password: it is refused by its
		// signature before anything is looked up, so the strict budget bought nothing here and cost
		// a great deal. Every client on one address shares the bucket, and a machine running the
		// phone simulator, the desktop app and a browser tab spent it in seconds — after which the
		// refresh that happened to land next came back 429, which a client could not tell apart
		// from "your session is over".
		for (int i = 0; i < 8; i++) {
			assertThat(call(REFRESH)).isEqualTo(HttpStatus.OK.value());
		}

		// Its own budget, though — not the general one.
		assertThat(call(REFRESH)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		// And spending it leaves the sign-in budget untouched, so somebody can still sign in.
		assertThat(call(LOGIN)).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void ssoDiscoveryIsNotStarvedByTheAuthBudget() throws Exception {
		// Drain the auth bucket the way a cold app start plus a sign-in attempt
		// or two does.
		for (int i = 0; i < 4; i++) {
			call(LOGIN);
		}
		assertThat(call(LOGIN)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

		// The buttons must still be discoverable.
		assertThat(call(SSO_PROVIDERS)).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void ssoDiscoveryIsStillRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			assertThat(call(SSO_PROVIDERS)).isEqualTo(HttpStatus.OK.value());
		}

		// It moved to the general budget, it did not leave rate limiting.
		assertThat(call(SSO_PROVIDERS)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
	}

	@Test
	void anythingUnusualFallsBackToTheStrictBudget() throws Exception {
		// Only the exact path is exempt: a trailing slash, a traversal attempt or
		// a query-shaped suffix must not buy the larger budget.
		for (int i = 0; i < 3; i++) {
			assertThat(call(SSO_PROVIDERS + "/")).isEqualTo(HttpStatus.OK.value());
		}

		assertThat(call(SSO_PROVIDERS + "/../login"))
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
	}

	@Test
	void eventStreamsAreNeverMetered() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(call("/api/v1/issues/i1/attachments/stream"))
					.isEqualTo(HttpStatus.OK.value());
		}
	}

	@Test
	void theMasterSwitchTurnsEverythingOff() throws Exception {
		properties.getRateLimit().setEnabled(false);

		for (int i = 0; i < 20; i++) {
			assertThat(call(LOGIN)).isEqualTo(HttpStatus.OK.value());
		}
	}
}
