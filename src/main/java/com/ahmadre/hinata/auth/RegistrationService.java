package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.GatewayService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Public self-registration + email-verification flow. Users sign up with a
 * password, prove ownership of their email via a one-time link (an app
 * deep-link, no backend pages), and are then either signed in immediately or —
 * when the admin-approval flag is on — held until an admin approves them.
 *
 * <p>Anti-enumeration: {@link #register} and {@link #resendVerification} never
 * reveal whether an email already exists; the controller always answers 202.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

	private static final Duration VERIFICATION_TTL = Duration.ofHours(24);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AuthPolicy authPolicy;
	private final UserService userService;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuthService authService;
	private final AuthMailService authMail;
	private final GatewayService gateway;
	private final NotificationService notifications;
	private final AuditService audit;

	/** Verify-email outcome: a token pair (signed in) or a pending-approval marker. */
	public record VerifyResult(boolean pendingApproval, String accessToken, String refreshToken,
			Long expiresIn, AuthController.UserResponse user) {
	}

	// --- Sign-up --------------------------------------------------------------

	public void register(String email, String username, String displayName, String rawPassword) {
		authPolicy.assertRegistrationEnabled();
		// Validate the password up front (generic 400, leaks nothing about the email).
		userService.validatePassword(rawPassword);
		String normalizedEmail = normalize(email);
		String normalizedUsername = username == null ? "" : username.trim();

		// Never reveal whether the email is already taken: silently succeed so the
		// response is identical to a fresh sign-up.
		if (users.existsByEmailIgnoreCase(normalizedEmail)) {
			return;
		}
		// Username uniqueness IS surfaced — the user needs to pick another one.
		if (normalizedUsername.isBlank() || users.existsByUsernameIgnoreCase(normalizedUsername)) {
			throw ApiException.conflict("error.user.usernameInUse");
		}

		User user = userService.createSelfRegistered(normalizedEmail, normalizedUsername, displayName,
				rawPassword);
		sendVerification(user);
		audit.event(AuditAction.USER_REGISTERED).actor(user).log();
	}

	public void resendVerification(String email) {
		authPolicy.assertRegistrationEnabled();
		users.findByEmailIgnoreCase(normalize(email))
				.filter(u -> !u.isEmailVerified() && u.getOrigin() == User.Origin.LOCAL
						&& u.getPasswordHash() != null)
				.ifPresent(this::sendVerification);
		// Always 202, regardless of whether a matching pending account existed.
	}

	// --- Verify email ---------------------------------------------------------

	public VerifyResult verifyEmail(String token, String ip, String userAgent) {
		authPolicy.assertLocalAuthEnabled();
		User user = resolve(token);
		user.setEmailVerified(true);
		user.setEmailVerificationTokenHash(null);
		user.setEmailVerificationExpiresAt(null);
		audit.event(AuditAction.EMAIL_VERIFIED).actor(user).log();

		if (authPolicy.requireAdminApproval()) {
			user.setAwaitingApproval(true);
			user.setActive(false);
			users.save(user);
			notifyAdmins(user);
			return new VerifyResult(true, null, null, null, null);
		}

		user.setAwaitingApproval(false);
		user.setActive(true);
		if (user.getJoinedAt() == null) {
			user.setJoinedAt(Instant.now());
		}
		User saved = users.save(user);
		TokenService.TokenPair pair = authService.issueWithSession(saved, ip, userAgent);
		return new VerifyResult(false, pair.accessToken(), pair.refreshToken(),
				pair.expiresInSeconds(), AuthController.UserResponse.from(saved));
	}

	// --- Internals ------------------------------------------------------------

	private void sendVerification(User user) {
		String secret = randomToken();
		user.setEmailVerificationTokenHash(passwordEncoder.encode(secret));
		user.setEmailVerificationExpiresAt(Instant.now().plus(VERIFICATION_TTL));
		users.save(user);
		// Deep link straight into the app's verify-email screen (no backend page).
		authMail.sendVerification(user,
				gateway.relayLink("/verify-email", user.getId() + "." + secret));
	}

	private void notifyAdmins(User newUser) {
		List<User> admins = users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
		notifications.notifyAdminsPendingApproval(admins, newUser);
		// Route the review link through the Connect gateway so it opens the native
		// app (the app only verifies the neutral connect domain, path /l/). The
		// new user's id travels as the relay "token"; the app forwards it to
		// /admin/users?token=<id> and the board opens that user's detail drawer.
		// (The id isn't secret — like every other id in an in-app URL.)
		String reviewUrl = gateway.relayLink("/admin/users", newUser.getId());
		for (User admin : admins) {
			authMail.sendApprovalRequest(admin, newUser, reviewUrl);
		}
	}

	/** Resolves a {@code userId.secret} verification token to its user, or 400s. */
	private User resolve(String token) {
		int dot = token == null ? -1 : token.indexOf('.');
		if (dot <= 0) throw ApiException.badRequest("error.auth.emailVerificationInvalid");
		String id = token.substring(0, dot);
		String secret = token.substring(dot + 1);
		User user = users.findById(id)
				.orElseThrow(() -> ApiException.badRequest("error.auth.emailVerificationInvalid"));
		if (user.getEmailVerificationTokenHash() == null
				|| user.getEmailVerificationExpiresAt() == null
				|| user.getEmailVerificationExpiresAt().isBefore(Instant.now())
				|| !passwordEncoder.matches(secret, user.getEmailVerificationTokenHash())) {
			throw ApiException.badRequest("error.auth.emailVerificationInvalid");
		}
		return user;
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
