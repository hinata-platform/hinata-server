package com.ahmadre.hinata.config;

import com.ahmadre.hinata.pat.PatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Bearer resolver used only by the {@code /mcp} security chain. A Personal Access
 * Token ({@code hn_pat_…}) is not a JWT, so if it reached the Nimbus decoder the
 * request would 401. Returning {@code null} for such tokens lets the
 * {@code PatAuthenticationFilter} own them, while genuine app JWTs still resolve
 * normally and flow to the JWT resource server.
 */
@Component
public class McpBearerTokenResolver implements BearerTokenResolver {

	private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

	@Override
	public String resolve(HttpServletRequest request) {
		String token = delegate.resolve(request);
		if (token != null && token.startsWith(PatService.TOKEN_PREFIX)) {
			return null;
		}
		return token;
	}
}
