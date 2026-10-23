package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The invitation a {@code userId.secret} token from an invitation mail opens. Whoever holds the
 * token has read that mail, which is what lets it complete the invitation: with a password in
 * {@link InviteController}, or with an SSO sign-in started from the invitation (HIN-179).
 */
@Component
@RequiredArgsConstructor
public class Invitations {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;

	/** The still pending account [token] opens, or empty for a malformed, used or expired one. */
	public Optional<User> pending(String token) {
		int dot = token == null ? -1 : token.indexOf('.');
		if (dot <= 0) {
			return Optional.empty();
		}
		String secret = token.substring(dot + 1);
		return users.findById(token.substring(0, dot))
				.filter(user -> user.isInvitePending() && user.getInviteTokenHash() != null
						&& user.getInviteExpiresAt() != null && user.getInviteExpiresAt().isAfter(Instant.now())
						&& passwordEncoder.matches(secret, user.getInviteTokenHash()));
	}

	/** As {@link #pending}, or 400 for a token that opens nothing. */
	public User resolve(String token) {
		return pending(token).orElseThrow(() -> ApiException.badRequest("error.user.inviteInvalid"));
	}
}
