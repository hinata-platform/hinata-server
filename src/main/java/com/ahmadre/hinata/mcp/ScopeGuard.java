package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.PatAuthenticationToken;
import com.ahmadre.hinata.pat.Scopes;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces MCP capability scopes on write tools. A request authenticated with a
 * Personal Access Token must carry the exact {@code SCOPE_<scope>} authority; a
 * request from an interactive app session (a JWT) implies every scope, since
 * that user already wields their full application authority through the UI. This
 * keeps PATs least-privilege without double-gating human callers.
 */
@Component
public class ScopeGuard {

	/**
	 * @throws ApiException 403 when the caller is a PAT lacking {@code scope}.
	 */
	public void require(String scope) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof PatAuthenticationToken) {
			String authority = Scopes.AUTHORITY_PREFIX + scope;
			boolean granted = authentication.getAuthorities().stream()
					.map(GrantedAuthority::getAuthority)
					.anyMatch(authority::equals);
			if (!granted) {
				throw ApiException.forbidden("error.mcp.scopeMissing", scope);
			}
		}
	}
}
