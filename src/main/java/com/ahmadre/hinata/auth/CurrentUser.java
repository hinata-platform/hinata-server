package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.pat.PatAuthenticationToken;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated {@link User} from the security context. Handles both
 * the interactive app JWT (with real-time session-revocation checks) and, on the
 * {@code /mcp} endpoint only, a {@link PatAuthenticationToken} from a verified
 * Personal Access Token.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

	private final UserRepository users;
	private final SessionService sessions;

	public User require() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt) {
			// Reject access tokens whose session has been revoked (admin "terminate
			// sessions", a sign-out from another device, a password reset, …) so
			// revocation takes effect on the very next request rather than waiting
			// for the token to expire. Session-less legacy tokens (null sid) pass.
			String sid = TokenService.sessionId(jwt.getToken());
			if (sid != null && !sessions.isActive(sid)) {
				throw ApiException.unauthorized("error.auth.sessionRevoked");
			}
			return users.findById(jwt.getToken().getSubject())
					.filter(User::isActive)
					.orElseThrow(() -> ApiException.unauthorized("error.auth.unknownUser"));
		}
		// Personal Access Token (MCP endpoint only). The token was already verified
		// — not revoked, not expired — by PatAuthenticationFilter; resolve the owner
		// and apply the same active-account gate as the JWT path.
		if (authentication instanceof PatAuthenticationToken pat) {
			return users.findById(pat.getUserId())
					.filter(User::isActive)
					.orElseThrow(() -> ApiException.unauthorized("error.auth.unknownUser"));
		}
		throw ApiException.unauthorized("error.auth.required");
	}

	public String requireId() {
		return require().getId();
	}

	/** The session id ({@code sid}) of the calling access token, or null (legacy token). */
	public String currentSessionId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt) {
			return com.ahmadre.hinata.auth.TokenService.sessionId(jwt.getToken());
		}
		return null;
	}
}
