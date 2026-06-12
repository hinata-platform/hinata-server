package hn.asta.hivora.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import hn.asta.hivora.auth.TokenService;
import hn.asta.hivora.auth.sso.MongoAuthorizationRequestRepository;
import hn.asta.hivora.auth.sso.SsoLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless JWT API security with optional OAuth2/OIDC and SAML2 SSO login.
 * Hardened headers and strict-by-default authorization (OWASP A01/A05/A07).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private final HivoraProperties properties;

	public SecurityConfig(HivoraProperties properties) {
		this.properties = properties;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	public JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS512).build();
	}

	private SecretKey secretKey() {
		return new SecretKeySpec(
				properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512");
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

	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			RateLimitFilter rateLimitFilter, SsoLoginSuccessHandler ssoSuccessHandler,
			hn.asta.hivora.auth.sso.SsoLoginFailureHandler ssoFailureHandler,
			MongoAuthorizationRequestRepository authorizationRequestRepository)
			throws Exception {
		http
			.csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.headers(headers -> headers
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
				.referrerPolicy(referrer -> referrer
					.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(
						"/api/v1/auth/login", "/api/v1/auth/refresh",
						"/api/v1/auth/sso/providers", "/api/v1/auth/sso/start/**",
						"/api/v1/meta", "/api/v1/setup/status", "/api/v1/setup",
						"/actuator/health", "/actuator/health/**",
						"/login/**", "/oauth2/**", "/saml2/**", "/error")
				.permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers("/actuator/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
			.oauth2Login(login -> login
				.authorizationEndpoint(endpoint -> endpoint
					.authorizationRequestRepository(authorizationRequestRepository))
				.successHandler(ssoSuccessHandler)
				.failureHandler(ssoFailureHandler))
			.saml2Login(saml -> saml
				.successHandler(ssoSuccessHandler)
				.failureHandler(ssoFailureHandler))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
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
