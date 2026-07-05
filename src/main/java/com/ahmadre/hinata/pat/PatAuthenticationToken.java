package com.ahmadre.hinata.pat;

import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Set;

/**
 * Authentication set in the security context once a Personal Access Token has
 * been verified on the {@code /mcp} endpoint. The principal is the owning user
 * id; each granted scope becomes a {@code SCOPE_<scope>} authority so
 * {@code mcp.ScopeGuard} can enforce least-privilege on write tools.
 */
@Getter
public class PatAuthenticationToken extends AbstractAuthenticationToken {

	private final String userId;
	private final String tokenId;

	public PatAuthenticationToken(String userId, String tokenId, Set<String> scopes) {
		super(scopes.stream()
				.map(scope -> (GrantedAuthority) new SimpleGrantedAuthority(Scopes.AUTHORITY_PREFIX + scope))
				.toList());
		this.userId = userId;
		this.tokenId = tokenId;
		setAuthenticated(true);
	}

	/** The presented secret is intentionally not retained. */
	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public Object getPrincipal() {
		return userId;
	}
}
