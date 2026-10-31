package com.ahmadre.hinata.config;

import com.ahmadre.hinata.common.TestMongo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this server answers without a token (HIN-26, HIN-167). Content of an instance reaches
 * only its members: that keeps an instance a hosting service and not an online platform (DSA
 * Art. 3 lit. k, recital 14), and the moderation rests on it. So every way in is read from the
 * running server and held against what was reviewed: each route of each handler mapping, the
 * filters of each security chain, which can answer a request before any rule asks for a token,
 * and every servlet and servlet filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class UnauthenticatedSurfaceTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** Routes anyone may call without a token, as their mapping declares them; each one reviewed. */
	static final Set<String> PUBLIC_ROUTES = Set.of(
			// Signing in, joining and getting back in: these hand out the token.
			"POST /api/v1/auth/login",
			"POST /api/v1/auth/2fa",
			"POST /api/v1/auth/refresh",
			"POST /api/v1/auth/register",
			"POST /api/v1/auth/verify-email",
			"POST /api/v1/auth/resend-verification",
			"POST /api/v1/auth/reset/request",
			"POST /api/v1/auth/reset/accept",
			"GET /api/v1/auth/invite/info",
			"POST /api/v1/auth/invite/accept",
			"GET /api/v1/auth/sso/providers",
			"GET /api/v1/auth/sso/start/{registrationId}",
			"POST /api/v1/auth/sso/exchange",
			// Links from a mail or a calendar subscription, each carrying its own secret.
			"GET /api/v1/me/email-change/confirm",
			"GET /api/v1/me/password-reset/confirm",
			"POST /api/v1/me/password-reset/confirm",
			"GET /api/v1/me/export.pdf",
			"GET /api/v1/shifts/feed/{token}.ics",
			// What an app learns about a server before signing in: name, logo, contact for reports.
			"GET /api/v1/meta",
			"GET /api/v1/meta/logo",
			// The house rules and the form for reports from people without an account (HIN-175, DSA
			// Art. 14 and 16): a token per form, bound to the address, limits in the database, nothing
			// echoed back.
			"GET /api/v1/public/rules",
			"GET /api/v1/public/report",
			"POST /api/v1/public/report",
			// Profile pictures for mails and pushes.
			"GET /api/v1/users/{id}/avatar",
			// The first start of a new server; refused once it is set up.
			"GET /api/v1/setup/status",
			"POST /api/v1/setup",
			// Git providers calling back; a webhook counts only with the signature of a connected repository.
			"GET /api/v1/git/oauth/callback",
			"POST /api/v1/git/webhooks/github",
			"POST /api/v1/git/webhooks/gitlab",
			"POST /api/v1/git/webhooks/bitbucket",
			// OAuth for AI clients connecting to /mcp: discovery, registration, consent, token.
			"GET /.well-known/oauth-authorization-server",
			"GET /.well-known/oauth-protected-resource/mcp",
			"POST /oauth/register",
			"GET /oauth/authorize",
			"POST /oauth/token",
			// Hinata Connect checking that this server controls its domain.
			"GET /.well-known/hinata-connect-challenge",
			// Liveness for the container runtime, status only.
			"GET /actuator/health",
			"GET /actuator/health/**",
			// The description of the API, off with HINATA_DOCS_ENABLED=false; nothing of the instance.
			"GET /v3/api-docs",
			"GET /docs",
			"GET /docs/scalar.js",
			"ANY /webjars/**",
			"ANY /error");

	/**
	 * The filters of each security chain in order: docs, MCP, everything else. Some answer a request
	 * themselves, before any rule asks for a token: CORS preflights, /logout (a redirect; a token is
	 * no session), the RFC 9728 metadata at /.well-known/oauth-protected-resource, the redirects to
	 * an identity provider (/oauth2/authorization/*, /saml2/authenticate/*) and the returns from one
	 * (/login/oauth2/code/*, /login/saml2/sso/*), and the rate limit.
	 */
	static final List<List<String>> CHAIN_FILTERS = List.of(
			List.of("DisableEncodeUrlFilter", "WebAsyncManagerIntegrationFilter", "SecurityContextHolderFilter",
					"HeaderWriterFilter", "LogoutFilter", "RequestCacheAwareFilter",
					"SecurityContextHolderAwareRequestFilter", "AnonymousAuthenticationFilter",
					"ExceptionTranslationFilter", "AuthorizationFilter"),
			List.of("DisableEncodeUrlFilter", "WebAsyncManagerIntegrationFilter", "SecurityContextHolderFilter",
					"HeaderWriterFilter", "CorsFilter", "LogoutFilter", "OAuth2ProtectedResourceMetadataFilter",
					"RateLimitFilter", "PatAuthenticationFilter", "BearerTokenAuthenticationFilter",
					"SecurityContextHolderAwareRequestFilter", "AnonymousAuthenticationFilter",
					"SessionManagementFilter", "ExceptionTranslationFilter", "AuthorizationFilter"),
			List.of("DisableEncodeUrlFilter", "WebAsyncManagerIntegrationFilter", "SecurityContextHolderFilter",
					"HeaderWriterFilter", "CorsFilter", "LogoutFilter", "OAuth2AuthorizationRequestRedirectFilter",
					"Saml2WebSsoAuthenticationRequestFilter", "OAuth2ProtectedResourceMetadataFilter",
					"OAuth2LoginAuthenticationFilter", "Saml2WebSsoAuthenticationFilter", "RateLimitFilter",
					"BearerTokenAuthenticationFilter", "SecurityContextHolderAwareRequestFilter",
					"AnonymousAuthenticationFilter", "SessionManagementFilter", "ExceptionTranslationFilter",
					"AuthorizationFilter"));

	static final Set<String> SERVLETS = Set.of("dispatcherServlet");

	/**
	 * Filters the container runs around every request. The rate limit also runs once outside the
	 * chains; Tomcat's WebSocket filter acts only on WebSocket endpoints, and the server registers none.
	 * The doc form filter (HIN-181) only notes which form of a rich-text document the request asks
	 * for and adds a {@code Vary} header; it answers nothing itself.
	 */
	static final Set<String> SERVLET_FILTERS = Set.of("Tomcat WebSocket (JSR356) Filter", "characterEncodingFilter",
			"docFormFilter", "formContentFilter", "rateLimitFilter", "requestContextFilter",
			"trustedForwardedHeaderFilter", "springSecurityFilterChain", "webMvcObservationFilter");

	private static final String REVIEW = "Whatever answers without a token can reach people outside the "
			+ "organisation. Review it, then list it here with its reason and in the docs (security.md, "
			+ "\"What is reachable without signing in\").";

	@LocalServerPort
	private int port;
	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("every route outside the reviewed list asks for a token, with every method")
	void everyRouteOutsideTheListAsksForAToken() {
		assertThat(ServerSurface.answeredWithoutToken(ServerSurface.routes(context), PUBLIC_ROUTES, port))
				.as(REVIEW)
				.isEmpty();
	}

	@Test
	@DisplayName("every reviewed route still exists and is still open, so the list says no more than the server")
	void everyListedRouteExistsAndIsOpen() {
		assertThat(ServerSurface.staleEntries(PUBLIC_ROUTES, ServerSurface.routes(context), context))
				.as("A reviewed route is gone or now needs a token: take it off the list, here and in the docs.")
				.isEmpty();
	}

	@Test
	@DisplayName("every filter, servlet and servlet filter that could answer by itself is reviewed")
	void everyFilterAndServletIsReviewed() {
		assertThat(ServerSurface.chainFilters(context)).as(REVIEW).isEqualTo(CHAIN_FILTERS);
		assertThat(ServerSurface.servlets(context)).as(REVIEW).isEqualTo(SERVLETS);
		assertThat(ServerSurface.servletFilters(context)).as(REVIEW).isEqualTo(SERVLET_FILTERS);
	}
}
