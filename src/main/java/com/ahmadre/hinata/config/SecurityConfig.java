package com.ahmadre.hinata.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.auth.sso.MongoAuthorizationRequestRepository;
import com.ahmadre.hinata.auth.sso.SsoLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Stateless JWT API security with optional OAuth2/OIDC and SAML2 SSO login.
 * Hardened headers and strict-by-default authorization (OWASP A01/A05/A07).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private final HinataProperties properties;
	private final com.ahmadre.hinata.me.SessionService sessions;

	public SecurityConfig(HinataProperties properties,
			com.ahmadre.hinata.me.SessionService sessions) {
		this.properties = properties;
		this.sessions = sessions;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	public JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
	}

	/**
	 * General-purpose decoder used by the {@code /auth/refresh} endpoint, which
	 * must be able to decode refresh tokens. The API resource server uses a
	 * stricter, access-token-only decoder ({@link #accessTokenJwtDecoder()}).
	 */
	@Bean
	public JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS512).build();
	}

	/**
	 * Resource-server decoder that rejects anything other than an access token,
	 * so a (long-lived) refresh token can never be used as a bearer token for
	 * API access (OWASP A07/A01).
	 */
	private JwtDecoder accessTokenJwtDecoder() {
		NimbusJwtDecoder decoder =
				NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS512).build();
		OAuth2Error error = new OAuth2Error("invalid_token",
				"Only access tokens are accepted on the API", null);
		OAuth2TokenValidator<Jwt> accessOnly = jwt ->
				TokenService.TYPE_ACCESS.equals(jwt.getClaimAsString(TokenService.CLAIM_TYPE))
						? OAuth2TokenValidatorResult.success()
						: OAuth2TokenValidatorResult.failure(error);
		// Reject access tokens whose session has been revoked (admin "terminate
		// sessions", password reset, deactivation, or the user signing a device
		// out). Without this an already-issued access token would keep working
		// until it expired (up to its full lifetime), so revocation would not take
		// effect in real time. Session-less legacy/service tokens (null sid) pass.
		OAuth2Error revoked = new OAuth2Error("invalid_token", "error.auth.sessionRevoked", null);
		OAuth2TokenValidator<Jwt> sessionAlive = jwt -> {
			String sid = jwt.getClaimAsString(TokenService.CLAIM_SID);
			return (sid == null || sessions.isActive(sid))
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(revoked);
		};
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(), accessOnly, sessionAlive));
		return decoder;
	}

	private SecretKey secretKey() {
		return new SecretKeySpec(
				properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512");
	}

	/**
	 * OIDC ID-token decoder with generous JWKS-fetch timeouts. The default
	 * decoder uses a short read timeout and no retry, so a single slow/cold TLS
	 * connection to the IdP's JWKS endpoint (typical on the first login or behind
	 * a tunnel) fails the whole login with "invalid_id_token: Read timed out".
	 * {@link NimbusJwtDecoder} caches the key set after the first successful
	 * fetch, so the longer timeout is only ever paid once. Validation (issuer,
	 * audience, nonce, expiry) is unchanged — {@link OidcIdTokenValidator} is the
	 * same validator the default factory installs.
	 */
	@Bean
	public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
		return registration -> {
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(Duration.ofSeconds(15));
			requestFactory.setReadTimeout(Duration.ofSeconds(30));
			NimbusJwtDecoder decoder = NimbusJwtDecoder
					.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
					.restOperations(new RestTemplate(requestFactory))
					.build();
			OAuth2TokenValidator<Jwt> validator = new OidcIdTokenValidator(registration);
			decoder.setJwtValidator(validator);
			return decoder;
		};
	}

	/** Separate, higher-priority chain for API docs paths — relaxed CSP so Scalar UI can load. */
	@Bean
	@Order(1)
	public SecurityFilterChain docsFilterChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/v3/api-docs/**", "/docs/**", "/docs", "/scalar/**", "/webjars/**")
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(
						"default-src 'self'; script-src 'self' 'unsafe-inline'; "
						+ "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
						+ "font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")))
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	/**
	 * Dedicated chain for the MCP endpoint. Sits ahead of the catch-all so that
	 * Personal Access Token auth is confined to {@code /mcp} and can never grant
	 * access to the regular REST API (where scopes are not enforced). Accepts a
	 * PAT ({@code hn_pat_…}, handled by {@link PatAuthenticationFilter}), a Phase-2
	 * MCP OAuth access token ({@code type=mcp}, scoped) or a normal app
	 * access-token JWT — all resolve to the same authenticated user, so the
	 * existing service-layer ACLs apply unchanged inside the MCP tools.
	 */
	@Bean
	@Order(2)
	public SecurityFilterChain mcpFilterChain(HttpSecurity http,
			RateLimitFilter rateLimitFilter,
			com.ahmadre.hinata.pat.PatAuthenticationFilter patAuthenticationFilter,
			McpBearerTokenResolver mcpBearerTokenResolver) throws Exception {
		http
			.securityMatcher("/mcp", "/mcp/**")
			.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.headers(headers -> headers
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.crossOriginResourcePolicy(corp -> corp
					.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
				.referrerPolicy(referrer -> referrer
					.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2
				.bearerTokenResolver(mcpBearerTokenResolver)
				.authenticationEntryPoint(mcpAuthenticationEntryPoint())
				.jwt(jwt -> jwt
					.decoder(mcpResourceJwtDecoder())
					.jwtAuthenticationConverter(mcpJwtAuthenticationConverter())))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(mcpAuthenticationEntryPoint()))
			.addFilterBefore(rateLimitFilter,
					org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(patAuthenticationFilter,
					org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	/** This server's issuer identifier (its public base URL, no trailing slash). */
	private String mcpIssuer() {
		String base = properties.getBaseUrl();
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	/** The canonical MCP resource URI that Phase-2 OAuth access tokens are bound to. */
	private String mcpResource() {
		return mcpIssuer() + "/mcp";
	}

	/**
	 * Resource-server decoder for the {@code /mcp} chain: accepts an app access
	 * token ({@code type=access}) or a Phase-2 MCP OAuth token ({@code type=mcp}),
	 * enforces real-time session revocation for session-bearing tokens, and binds
	 * MCP tokens to this server's resource audience (RFC 8707). Refresh tokens and
	 * every other token type are rejected.
	 */
	private JwtDecoder mcpResourceJwtDecoder() {
		NimbusJwtDecoder decoder =
				NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS512).build();
		OAuth2Error typeError = new OAuth2Error("invalid_token",
				"Only access or MCP tokens are accepted on /mcp", null);
		OAuth2TokenValidator<Jwt> typeOk = jwt -> {
			String type = jwt.getClaimAsString(TokenService.CLAIM_TYPE);
			return (TokenService.TYPE_ACCESS.equals(type) || TokenService.TYPE_MCP.equals(type))
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(typeError);
		};
		OAuth2Error revoked = new OAuth2Error("invalid_token", "Session has been revoked", null);
		OAuth2TokenValidator<Jwt> sessionAlive = jwt -> {
			String sid = jwt.getClaimAsString(TokenService.CLAIM_SID);
			return (sid == null || sessions.isActive(sid))
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(revoked);
		};
		OAuth2Error audienceError = new OAuth2Error("invalid_token",
				"Token audience does not match the MCP resource", null);
		String resource = mcpResource();
		OAuth2TokenValidator<Jwt> audienceOk = jwt -> {
			if (!TokenService.TYPE_MCP.equals(jwt.getClaimAsString(TokenService.CLAIM_TYPE))) {
				return OAuth2TokenValidatorResult.success();
			}
			return (jwt.getAudience() != null && jwt.getAudience().contains(resource))
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(audienceError);
		};
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(), typeOk, sessionAlive, audienceOk));
		return decoder;
	}

	/**
	 * Authority mapping for the {@code /mcp} chain: an MCP OAuth token's
	 * space-delimited {@code scope} claim becomes {@code SCOPE_*} authorities (so
	 * {@code ScopeGuard} enforces least privilege); an app access token keeps its
	 * {@code ROLE_*} authorities (a full session that implies every scope).
	 */
	private JwtAuthenticationConverter mcpJwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName(TokenService.CLAIM_ROLES);
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			if (TokenService.TYPE_MCP.equals(jwt.getClaimAsString(TokenService.CLAIM_TYPE))) {
				String scope = jwt.getClaimAsString(TokenService.CLAIM_SCOPE);
				if (scope == null || scope.isBlank()) {
					return List.of();
				}
				return Arrays.stream(scope.trim().split("\\s+"))
						.filter(s -> !s.isBlank())
						.map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
						.toList();
			}
			return roles.convert(jwt);
		});
		return converter;
	}

	/**
	 * On a 401 at {@code /mcp}, advertise the RFC 9728 protected-resource metadata
	 * URL via {@code WWW-Authenticate} so an OAuth-capable client (Claude) can
	 * discover the authorization server and start the connect flow.
	 */
	private AuthenticationEntryPoint mcpAuthenticationEntryPoint() {
		String base = properties.getBaseUrl();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		String metadataUrl = base + "/.well-known/oauth-protected-resource";
		return (request, response, authException) -> {
			response.setHeader("WWW-Authenticate",
					"Bearer resource_metadata=\"" + metadataUrl + "\"");
			response.sendError(HttpStatus.UNAUTHORIZED.value());
		};
	}

	@Bean
	@Order(3)
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			RateLimitFilter rateLimitFilter, SsoLoginSuccessHandler ssoSuccessHandler,
			com.ahmadre.hinata.auth.sso.SsoLoginFailureHandler ssoFailureHandler,
			MongoAuthorizationRequestRepository authorizationRequestRepository,
			LocalizedAuthenticationEntryPoint authenticationEntryPoint)
			throws Exception {
		http
			.csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			// A bearer-token API has no use for the saved-request cache; disabling
			// it stops Spring from creating a JSESSIONID (without SameSite/Secure)
			// on every 401 (OWASP A05). Sessions remain available only for the
			// SAML2/OAuth2 login redirect flows that genuinely need them.
			.requestCache(cache -> cache.disable())
			.headers(headers -> headers
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.crossOriginResourcePolicy(corp -> corp
					.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
				.referrerPolicy(referrer -> referrer
					.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
						"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/2fa",
						"/api/v1/auth/sso/providers", "/api/v1/auth/sso/start/**",
						"/api/v1/auth/invite/**", "/api/v1/auth/reset/**",
						"/api/v1/auth/register", "/api/v1/auth/verify-email",
						"/api/v1/auth/resend-verification",
						"/api/v1/me/email-change/confirm", "/api/v1/me/password-reset/confirm",
						"/api/v1/me/export.pdf",
						"/api/v1/meta", "/api/v1/meta/logo",
						"/api/v1/users/*/avatar",
						"/api/v1/git/oauth/callback", "/api/v1/git/webhooks/**",
						"/api/v1/setup/status", "/api/v1/setup",
						"/actuator/health", "/actuator/health/**",
						// MCP OAuth 2.1: discovery + protocol endpoints are public
						// (security is PKCE + exact redirect + client auth). The
						// consent endpoints under /api/v1/oauth/** stay authenticated.
						"/.well-known/oauth-protected-resource",
						"/.well-known/oauth-protected-resource/**",
						"/.well-known/oauth-authorization-server",
						// Hinata Connect domain-control proof (fetched by the gateway).
						"/.well-known/hinata-connect-challenge",
						"/oauth/register", "/oauth/authorize", "/oauth/token",
						"/login/**", "/oauth2/**", "/saml2/**", "/error")
				.permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers("/actuator/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2
				.authenticationEntryPoint(authenticationEntryPoint)
				// RFC 9728: customize Spring Security's built-in metadata endpoint
				// (/.well-known/oauth-protected-resource) so it advertises the /mcp
				// resource and this server as its authorization server, letting an
				// OAuth-capable AI client discover how to connect.
				.protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(metadata -> {
					metadata.resource(mcpResource());
					metadata.authorizationServer(mcpIssuer());
					metadata.scopes(scopes -> scopes.addAll(com.ahmadre.hinata.pat.Scopes.ALL));
				}))
				.jwt(jwt -> jwt
					.decoder(accessTokenJwtDecoder())
					.jwtAuthenticationConverter(jwtAuthenticationConverter())))
			.oauth2Login(login -> login
				.authorizationEndpoint(endpoint -> endpoint
					.authorizationRequestRepository(authorizationRequestRepository))
				.successHandler(ssoSuccessHandler)
				.failureHandler(ssoFailureHandler))
			.saml2Login(saml -> saml
				.successHandler(ssoSuccessHandler)
				.failureHandler(ssoFailureHandler))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(authenticationEntryPoint))
			.addFilterBefore(rateLimitFilter,
					org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	private JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter granted = new JwtGrantedAuthoritiesConverter();
		granted.setAuthoritiesClaimName(TokenService.CLAIM_ROLES);
		granted.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			// Refresh tokens are never valid for API access (OWASP A07).
			if (TokenService.isRefreshToken(jwt)) {
				return List.of();
			}
			return granted.convert(jwt);
		});
		return converter;
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		// "ngrok-skip-browser-warning" lets web/XHR clients bypass the ngrok
		// free-tier interstitial; it must be allow-listed or the CORS preflight
		// for that custom header fails in browsers (Flutter web).
		config.setAllowedHeaders(
				List.of("Authorization", "Content-Type", "Accept", "ngrok-skip-browser-warning"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
