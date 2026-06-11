package hn.asta.hivora.user;

import hn.asta.hivora.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

	public static final int MIN_PASSWORD_LENGTH = 10;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;

	public User get(String id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
	}

	public User createLocal(String email, String username, String displayName, String rawPassword,
			Set<Role> roles) {
		validatePassword(rawPassword);
		if (users.existsByEmailIgnoreCase(email)) {
			throw ApiException.conflict("E-mail already in use");
		}
		if (users.existsByUsernameIgnoreCase(username)) {
			throw ApiException.conflict("Username already in use");
		}
		return users.save(User.builder()
				.email(email.toLowerCase(Locale.ROOT))
				.username(username)
				.displayName(displayName)
				.passwordHash(passwordEncoder.encode(rawPassword))
				.roles(roles)
				.origin(User.Origin.LOCAL)
				.build());
	}

	/** Find-or-create for accounts arriving via OIDC, SAML or LDAP. */
	public User provisionSso(String email, String displayName, User.Origin origin) {
		return users.findByEmailIgnoreCase(email).orElseGet(() -> users.save(User.builder()
				.email(email.toLowerCase(Locale.ROOT))
				.username(uniqueUsernameFrom(email))
				.displayName(displayName != null && !displayName.isBlank() ? displayName : email)
				.roles(Set.of(Role.MEMBER))
				.origin(origin)
				.build()));
	}

	public void changePassword(User user, String currentPassword, String newPassword) {
		if (user.getPasswordHash() == null
				|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw ApiException.badRequest("Current password is incorrect");
		}
		validatePassword(newPassword);
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		users.save(user);
	}

	public void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
			throw ApiException.badRequest(
					"Password must have at least " + MIN_PASSWORD_LENGTH + " characters");
		}
	}

	private String uniqueUsernameFrom(String email) {
		String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
		String candidate = base;
		int suffix = 1;
		while (users.existsByUsernameIgnoreCase(candidate)) {
			candidate = base + suffix++;
		}
		return candidate;
	}
}
