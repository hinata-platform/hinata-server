package com.ahmadre.hinata.pat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates Personal Access Tokens on the {@code /mcp} endpoint. Only acts on
 * {@code hn_pat_…} bearers — real app JWTs are ignored here and handled by the
 * JWT resource server. On a valid PAT it installs a {@link PatAuthenticationToken}
 * (scopes as authorities); an invalid/expired/revoked PAT leaves the context
 * empty so the chain's entry point returns 401.
 */
@Component
@RequiredArgsConstructor
public class PatAuthenticationFilter extends OncePerRequestFilter {

	private final PatService patService;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String token = bearer(request);
		if (token != null && token.startsWith(PatService.TOKEN_PREFIX)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			patService.verify(token).ifPresent(pat -> {
				PatAuthenticationToken authentication =
						new PatAuthenticationToken(pat.getUserId(), pat.getId(), pat.getScopes());
				SecurityContext context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(authentication);
				SecurityContextHolder.setContext(context);
			});
		}
		chain.doFilter(request, response);
	}

	private static String bearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return header.substring(7).trim();
		}
		return null;
	}
}
