package hn.asta.hivora.auth.sso;

import hn.asta.hivora.auth.TokenService;
import hn.asta.hivora.config.HivoraProperties;
import hn.asta.hivora.user.User;
import hn.asta.hivora.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * browser is redirected into the app via its deep link scheme, carrying a
 * fresh token pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoLoginSuccessHandler implements AuthenticationSuccessHandler {

	private final UserService userService;
	private final TokenService tokens;
	private final HivoraProperties properties;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		User user = provision(authentication);
		TokenService.TokenPair pair = tokens.issue(user);
		String target = properties.getApp().getCallbackScheme() + "://auth-callback"
				+ "?access_token=" + URLEncoder.encode(pair.accessToken(), StandardCharsets.UTF_8)
				+ "&refresh_token=" + URLEncoder.encode(pair.refreshToken(), StandardCharsets.UTF_8);
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
