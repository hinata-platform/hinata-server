package hn.asta.hivora.auth;

import hn.asta.hivora.auth.sso.LdapAuthenticator;
import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.setup.SettingsService;
import hn.asta.hivora.user.User;
import hn.asta.hivora.user.UserRepository;
import hn.asta.hivora.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository users;
	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokens;
	private final LoginAttemptService attempts;
	private final SettingsService settings;
	private final LdapAuthenticator ldap;

	public record LoginResult(User user, TokenService.TokenPair tokens) {
	}

	/** Local credentials first, then LDAP fallback when enabled. */
	public LoginResult login(String identifier, String password, String ip) {
		attempts.assertNotBlocked(identifier, ip);

		Optional<User> resolved = localLogin(identifier, password)
				.or(() -> ldapLogin(identifier, password));

		User user = resolved.orElseThrow(() -> {
			attempts.recordFailure(identifier, ip);
			return ApiException.unauthorized("error.auth.invalidCredentials");
		});
		if (!user.isActive()) {
			throw ApiException.forbidden("error.auth.accountDeactivated");
		}
		attempts.recordSuccess(identifier, ip);
		return new LoginResult(user, tokens.issue(user));
	}

	private Optional<User> localLogin(String identifier, String password) {
		return users.findByEmailIgnoreCase(identifier)
				.or(() -> users.findByUsernameIgnoreCase(identifier))
				.filter(u -> u.getPasswordHash() != null
						&& passwordEncoder.matches(password, u.getPasswordHash()));
	}

	private Optional<User> ldapLogin(String identifier, String password) {
		return ldap.authenticate(settings.get().getLdap(), identifier, password)
				.map(ldapUser -> userService.provisionSso(
						ldapUser.email(), ldapUser.displayName(), User.Origin.LDAP));
	}

	public TokenService.TokenPair refresh(User user) {
		if (!user.isActive()) {
			throw ApiException.forbidden("error.auth.accountDeactivated");
		}
		return tokens.issue(user);
	}
}
