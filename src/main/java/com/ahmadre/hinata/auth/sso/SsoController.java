package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.auth.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Tag(name = "SSO")
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

	/** Session attribute holding the validated web origin to return to after SSO. */
	public static final String SESSION_RETURN_ORIGIN = "hinata.sso.returnOrigin";

	/**
	 * Cookie mirroring {@link #SESSION_RETURN_ORIGIN}. Sessions are in-memory and
	 * die with the server; the cookie keeps the web return target alive so even a
	 * failed login lands back in the web app instead of a dead deep link.
	 */
	public static final String COOKIE_RETURN_ORIGIN = "HINATA_SSO_RETURN";

	private static final String START_PATH = "/api/v1/auth/sso/start/";

	private static final Set<String> KNOWN_IDS = Set.of(
			DynamicClientRegistrationRepository.OIDC_ID,
			DynamicClientRegistrationRepository.OAUTH2_ID,
			DynamicRelyingPartyRegistrationRepository.SAML_ID);

	private final SettingsService settings;
	private final HinataProperties properties;
	private final SsoHandoffService handoff;

	public record SsoProvider(String id, String displayName, String loginUrl) {
	}

	public record ExchangeRequest(@NotBlank String code) {
	}

	public record ExchangeResponse(String accessToken, String refreshToken, long expiresIn) {
	}

	/**
	 * Redeems the single-use handoff code delivered to {@code /auth-callback?code=}
	 * for the actual token pair. Unauthenticated by design (the user is mid-login);
	 * security comes from the code being high-entropy, short-lived and single-use.
	 * Keeps bearer tokens out of URLs / logs (they only ever travel in this POST
	 * response body over TLS).
	 */
	@Operation(summary = "Exchange an SSO handoff code for tokens",
			description = "Redeems the single-use code from the SSO callback redirect for the "
					+ "access/refresh token pair. No authentication required.")
	@SecurityRequirements
	@PostMapping("/exchange")
	public ExchangeResponse exchange(@RequestBody @Valid ExchangeRequest request) {
		TokenService.TokenPair pair = handoff.redeem(request.code());
		if (pair == null) {
			throw ApiException.badRequest("error.sso.invalidCode");
		}
		return new ExchangeResponse(pair.accessToken(), pair.refreshToken(),
				pair.expiresInSeconds());
	}

	@Operation(summary = "List enabled SSO providers", description = "Used by the app to render SSO login buttons. No authentication required.")
	@SecurityRequirements
	@GetMapping("/providers")
	public List<SsoProvider> providers() {
		ServerSettings current = settings.get();
		List<SsoProvider> providers = new ArrayList<>();
		if (current.getOidc().isEnabled()) {
			providers.add(new SsoProvider("oidc", current.getOidc().getDisplayName(),
					START_PATH + DynamicClientRegistrationRepository.OIDC_ID));
		}
		if (current.getOauth2().isEnabled()) {
			providers.add(new SsoProvider("oauth2", current.getOauth2().getDisplayName(),
					START_PATH + DynamicClientRegistrationRepository.OAUTH2_ID));
		}
		if (current.getSaml().isEnabled()) {
			providers.add(new SsoProvider("saml", current.getSaml().getDisplayName(),
					START_PATH + DynamicRelyingPartyRegistrationRepository.SAML_ID));
		}
		return providers;
	}

	/**
	 * Entry point for the SSO flow. Native apps call it without parameters and
	 * receive the token pair via the {@code hinata://auth-callback} deep link.
	 * The web app passes {@code ?return=<origin>}; after login the browser is
	 * redirected to {@code <origin>/#/auth-callback} instead. The origin must
	 * match one of the configured CORS origins (open-redirect protection).
	 */
	@Operation(summary = "Start an SSO login flow",
			description = "Redirects to the identity provider. Web clients pass `return=<origin>` "
					+ "(must be a configured CORS origin) to receive the tokens back in the browser.")
	@SecurityRequirements
	@GetMapping("/start/{registrationId}")
	public void start(@PathVariable String registrationId,
			@RequestParam(name = "return", required = false) String returnOrigin,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!KNOWN_IDS.contains(registrationId)) {
			throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
					"error.sso.unknownProvider");
		}
		if (returnOrigin != null && !returnOrigin.isBlank()) {
			String origin = validatedOrigin(returnOrigin);
			request.getSession(true).setAttribute(SESSION_RETURN_ORIGIN, origin);
			ResponseCookie cookie = ResponseCookie.from(COOKIE_RETURN_ORIGIN, origin)
					.httpOnly(true)
					.secure(request.isSecure())
					.path("/")
					.maxAge(Duration.ofMinutes(10))
					.sameSite("Lax")
					.build();
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		}
		String path = DynamicRelyingPartyRegistrationRepository.SAML_ID.equals(registrationId)
				? "/saml2/authenticate/" + registrationId
				: "/oauth2/authorization/" + registrationId;
		response.sendRedirect(path);
	}

	/**
	 * Resolves and clears the web return origin for the current SSO flow:
	 * session attribute first, cookie as restart-safe fallback. The value is
	 * re-validated against the CORS allowlist before use (open-redirect guard).
	 * Returns {@code null} for native flows.
	 */
	public static String consumeReturnOrigin(HttpServletRequest request,
			HttpServletResponse response, List<String> allowedOrigins) {
		String origin = null;
		var session = request.getSession(false);
		if (session != null && session.getAttribute(SESSION_RETURN_ORIGIN) instanceof String s) {
			origin = s;
			session.removeAttribute(SESSION_RETURN_ORIGIN);
		}
		if (origin == null && request.getCookies() != null) {
			for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
				if (COOKIE_RETURN_ORIGIN.equals(cookie.getName())) {
					origin = cookie.getValue();
				}
			}
		}
		ResponseCookie clear = ResponseCookie.from(COOKIE_RETURN_ORIGIN, "")
				.httpOnly(true).path("/").maxAge(0).sameSite("Lax").build();
		response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
		return origin != null && allowedOrigins.contains(origin) ? origin : null;
	}

	private String validatedOrigin(String candidate) {
		URI uri;
		try {
			uri = URI.create(candidate.trim());
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("error.sso.invalidReturnOrigin");
		}
		String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
		if (!properties.getCors().getAllowedOrigins().contains(origin)) {
			throw ApiException.badRequest("error.sso.originNotAllowed");
		}
		return origin;
	}
}
