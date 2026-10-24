package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.HouseRules;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>A scope that writes also asks whether the caller accepted the house rules (HIN-175), whatever
 * the credential: a token issued before the rules, or before a version that asks everybody again,
 * keeps reading and stops writing until its owner accepts. The store app writes over REST, not
 * here, so this blocks no client an update would break.
 */
@Component
@RequiredArgsConstructor
public class ScopeGuard {

	private final CurrentUser currentUser;
	private final ObjectProvider<HouseRules> houseRules;

	/**
	 * @throws ApiException 403 when the caller holds scoped authorities but is
	 *         missing {@code scope}; 409 when the scope writes and the caller has
	 *         still to accept the house rules.
	 */
	public void require(String scope) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return;
		}
		boolean scoped = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(a -> a.startsWith(Scopes.AUTHORITY_PREFIX));
		// An interactive app session (ROLE_*) has every scope.
		if (scoped) {
			String required = Scopes.AUTHORITY_PREFIX + scope;
			boolean granted = authentication.getAuthorities().stream()
					.map(GrantedAuthority::getAuthority)
					.anyMatch(required::equals);
			if (!granted) {
				throw ApiException.forbidden("error.mcp.scopeMissing", scope);
			}
		}
		if (Scopes.WRITES.contains(scope)) {
			HouseRules rules = houseRules.getIfAvailable();
			if (rules != null) {
				rules.requireAccepted(currentUser.require());
			}
		}
	}
}
