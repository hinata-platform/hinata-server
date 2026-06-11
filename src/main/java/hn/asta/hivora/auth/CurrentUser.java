package hn.asta.hivora.auth;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.user.User;
import hn.asta.hivora.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the authenticated {@link User} from the JWT in the security context. */
@Component
@RequiredArgsConstructor
public class CurrentUser {

	private final UserRepository users;

	public User require() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt) {
			return users.findById(jwt.getToken().getSubject())
					.filter(User::isActive)
					.orElseThrow(() -> ApiException.unauthorized("Unknown user"));
		}
		throw ApiException.unauthorized("Authentication required");
	}

	public String requireId() {
		return require().getId();
	}
}
