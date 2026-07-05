package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.Scopes;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces MCP capability scopes on tools. Any <em>scoped</em> credential — a
 * Personal Access Token or an OAuth 2.1 access token, both of which carry
 * {@code SCOPE_<scope>} authorities — must present the exact required scope. A
 * request from an interactive app session (a plain JWT, which carries only
 * {@code ROLE_*}) implies every scope, since that user already wields their full
 * application authority through the UI. This keeps machine tokens least-privilege
 * without double-gating human callers.
 */
@Component
public class ScopeGuard {

	/**
	 * @throws ApiException 403 when the caller holds scoped authorities but is
	 *         missing {@code scope}.
	 */
	public void require(String scope) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return;
		}
		boolean scoped = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(a -> a.startsWith(Scopes.AUTHORITY_PREFIX));
		if (!scoped) {
			return; // interactive app session (ROLE_*): full scope
		}
		String required = Scopes.AUTHORITY_PREFIX + scope;
		boolean granted = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(required::equals);
		if (!granted) {
			throw ApiException.forbidden("error.mcp.scopeMissing", scope);
		}
	}
}
