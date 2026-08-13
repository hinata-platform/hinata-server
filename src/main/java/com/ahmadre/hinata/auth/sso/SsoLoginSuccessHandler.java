package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * After a successful OAuth2/OIDC or SAML login the user is provisioned and the
 * browser is redirected back into the app, carrying a fresh token pair. Native
 * apps receive it via the deep link scheme; the web app (flow started through
 * {@code /api/v1/auth/sso/start?return=...}) via its own origin. The token
 * fragment after {@code #} is never sent to any server (browser-only).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoLoginSuccessHandler implements AuthenticationSuccessHandler {

	private final UserService userService;
	private final TokenService tokens;
	private final HinataProperties properties;
	private final SettingsService settings;
	private final SsoProfileMapper mapper;
	private final com.ahmadre.hinata.me.SessionService sessions;
	private final com.ahmadre.hinata.config.ClientIpResolver clientIpResolver;
	private final com.ahmadre.hinata.audit.AuditService audit;
	private final SsoHandoffService handoff;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// Success handlers run in the security filter chain, before the
		// DispatcherServlet resolves the request locale — seed it from the
		// browser's Accept-Language so a first-time SSO user is provisioned in
		// their own language (see UserService#provisionSso).
		LocaleContextHolder.setLocale(request.getLocale());
		User user = provision(authentication);
		com.ahmadre.hinata.me.RefreshSession session = sessions.start(user,
				clientIpResolver.resolve(request), request.getHeader("User-Agent"));
		TokenService.TokenPair pair = tokens.issue(user, session.getId());
		audit.event(com.ahmadre.hinata.audit.AuditAction.SSO_LOGIN).actor(user)
				.meta("provider", user.getOrigin().name()).log();
		// The token pair never travels in the URL: it is stashed behind a
		// short-lived, single-use handoff code the app POSTs to
		// /api/v1/auth/sso/exchange to redeem. A code in the query is safe to log
		// (useless after one redemption, TTL-expired otherwise) whereas bearer
		// tokens are not.
		String code = handoff.issue(pair);
		String query = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);

		String webOrigin = SsoController.consumeReturnOrigin(request, response,
				properties.getCors().getAllowedOrigins());
		String target = webOrigin != null
				? webOrigin + "/auth-callback?" + query
				: properties.getApp().getCallbackScheme() + "://auth-callback?" + query;
		response.sendRedirect(target);
	}

	/**
	 * Turns the authenticated principal into an account. Which attribute carries
	 * the display name or the position differs per IdP, so the mapping is read
	 * from the provider's own block in the admin settings instead of being
	 * hardcoded here (see {@link SsoProfileMapper}).
	 */
	private User provision(Authentication authentication) {
		ServerSettings current = settings.get();
		if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
			ServerSettings.Saml config = current.getSaml();
			SsoProfileMapper.SsoProfile profile =
					mapper.map(attributesOf(saml), config, saml.getName());
			return userService.provisionSso(profile, User.Origin.SAML, config.isSyncProfileOnLogin());
		}
		if (authentication.getPrincipal() instanceof OAuth2User oauth) {
			// One principal type covers both registrations; the registration id says
			// whose mapping applies (discovered OIDC vs. hand-configured OAuth2).
			ServerSettings.AttributeMapping config = isPlainOAuth2(authentication)
					? current.getOauth2()
					: current.getOidc();
			SsoProfileMapper.SsoProfile profile =
					mapper.map(oauth.getAttributes(), config, oauth.getName());
			return userService.provisionSso(profile, User.Origin.OIDC, config.isSyncProfileOnLogin());
		}
		throw new IllegalStateException("Unsupported SSO principal type");
	}

	private boolean isPlainOAuth2(Authentication authentication) {
		return authentication instanceof OAuth2AuthenticationToken token
				&& DynamicClientRegistrationRepository.OAUTH2_ID
						.equals(token.getAuthorizedClientRegistrationId());
	}

	/**
	 * A SAML assertion holds every attribute as a list. Widening the map to the
	 * mapper's {@code Map<String, Object>} (which flattens the lists) needs the
	 * wildcard of {@code unmodifiableMap} — a plain cast would not compile.
	 */
	private java.util.Map<String, Object> attributesOf(Saml2AuthenticatedPrincipal principal) {
		return java.util.Collections.unmodifiableMap(principal.getAttributes());
	}
}
