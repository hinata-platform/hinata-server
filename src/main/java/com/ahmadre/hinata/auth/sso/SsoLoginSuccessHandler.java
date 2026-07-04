package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
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
	private final com.ahmadre.hinata.me.SessionService sessions;
	private final com.ahmadre.hinata.config.ClientIpResolver clientIpResolver;
	private final com.ahmadre.hinata.audit.AuditService audit;

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
		String query = "access_token=" + URLEncoder.encode(pair.accessToken(), StandardCharsets.UTF_8)
				+ "&refresh_token=" + URLEncoder.encode(pair.refreshToken(), StandardCharsets.UTF_8);

		String webOrigin = SsoController.consumeReturnOrigin(request, response,
				properties.getCors().getAllowedOrigins());
		String target = webOrigin != null
				? webOrigin + "/auth-callback?" + query
				: properties.getApp().getCallbackScheme() + "://auth-callback?" + query;
		response.sendRedirect(target);
	}

	private User provision(Authentication authentication) {
		if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
			String email = firstAttribute(saml, "email", "mail", "urn:oid:0.9.2342.19200300.100.1.3")
					.orElse(saml.getName());
			String name = firstAttribute(saml, "displayName", "cn", "name").orElse(email);
			return userService.provisionSso(email, name, User.Origin.SAML);
		}
		if (authentication.getPrincipal() instanceof OAuth2User oauth) {
			String email = stringAttr(oauth, "email").orElse(oauth.getName());
			String name = stringAttr(oauth, "name")
					.or(() -> stringAttr(oauth, "preferred_username"))
					.orElse(email);
			return userService.provisionSso(email, name, User.Origin.OIDC);
		}
		throw new IllegalStateException("Unsupported SSO principal type");
	}

	private java.util.Optional<String> firstAttribute(Saml2AuthenticatedPrincipal principal,
			String... names) {
		for (String name : names) {
			String value = principal.getFirstAttribute(name);
			if (value != null && !value.isBlank()) {
				return java.util.Optional.of(value);
			}
		}
		return java.util.Optional.empty();
	}

	private java.util.Optional<String> stringAttr(OAuth2User user, String name) {
		Object value = user.getAttributes().get(name);
		return value instanceof String s && !s.isBlank()
				? java.util.Optional.of(s)
				: java.util.Optional.empty();
	}
}
