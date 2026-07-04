package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.AccountMailService;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints one-time password-reset tokens and emails the in-app reset deep-link.
 * Shared by the authenticated self-service flow ({@code MeService}) and the
 * public forgot-password initiator ({@link PasswordResetController}). The reset
 * link opens the app's reset screen directly (see {@code ResetPasswordScreen});
 * no backend page is rendered.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

	private static final Duration TTL = Duration.ofMinutes(30);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AccountMailService accountMail;
	private final GatewayService gateway;
	private final AuditService audit;
	private final AuthPolicy authPolicy;

	/**
	 * Public forgot-password entry point. Looks the user up by email and — only
	 * for a usable local account — sends a reset link. Always returns without
	 * signalling whether the address exists (anti-enumeration); the controller
	 * answers 202 regardless.
	 */
	public void requestByEmail(String email) {
		authPolicy.assertLocalAuthEnabled();
		if (email == null || email.isBlank()) return;
		users.findByEmailIgnoreCase(email.trim())
				.filter(u -> !u.isSso() && u.isActive() && u.isEmailVerified()
						&& u.getPasswordHash() != null)
				.ifPresent(this::sendFor);
	}

	/** Mints a fresh reset token for a known local user and emails the deep-link. */
	public void sendFor(User user) {
		if (user.isSso()) {
			throw ApiException.badRequest("error.me.passwordManagedByProvider");
		}
		String secret = randomToken();
		user.setPasswordResetTokenHash(passwordEncoder.encode(secret));
		user.setPasswordResetExpiresAt(Instant.now().plus(TTL));
		users.save(user);
		accountMail.sendPasswordReset(user,
				gateway.relayLink("/reset-password", user.getId() + "." + secret));
		audit.event(AuditAction.PASSWORD_RESET_REQUESTED).actor(user).log();
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
