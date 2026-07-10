package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Business logic behind the self-service {@code /me} surface: profile edits,
 * double-opt-in email change, password reset, TOTP 2FA, session revocation,
 * notification preferences, and the GDPR data-export / erasure flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String ISSUER = "hinata";

	private final UserRepository users;
	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final SessionService sessions;
	private final TotpService totp;
	private final RecoveryCodeService recoveryCodes;
	private final AccountMailService accountMail;
	private final HinataProperties properties;
	private final TeamRepository teams;
	private final ProjectService projects;
	private final com.ahmadre.hinata.notification.NotificationService notifications;
	private final com.ahmadre.hinata.notification.GatewayService gateway;
	private final AuditService audit;
	private final com.ahmadre.hinata.auth.TokenService tokens;
	private final com.ahmadre.hinata.auth.PasswordResetService passwordResetService;

	/** How long the e-mailed data-export download link stays valid. */
	private static final long EXPORT_TOKEN_TTL_SECONDS = 72 * 3600L;

	// --- Profile --------------------------------------------------------------

	public User updateProfile(User user, String displayName, String title, String locale) {
		if (displayName != null) user.setDisplayName(displayName.trim());
		if (title != null) user.setTitle(title.trim());
		if (locale != null) user.setLocale(locale);
		return users.save(user);
	}

	// --- Email change (double opt-in) ----------------------------------------

	public void requestEmailChange(User user, String newEmail) {
		if (user.isSso()) {
			throw ApiException.badRequest("error.me.emailManagedByProvider");
		}
		String normalized = newEmail.trim().toLowerCase(Locale.ROOT);
		if (normalized.equalsIgnoreCase(user.getEmail())) {
			throw ApiException.badRequest("error.me.emailUnchanged");
		}
		if (users.existsByEmailIgnoreCase(normalized)) {
			throw ApiException.conflict("error.user.emailInUse");
		}
		String secret = randomToken();
		user.setPendingEmail(normalized);
		user.setEmailChangeTokenHash(passwordEncoder.encode(secret));
		user.setEmailChangeExpiresAt(Instant.now().plusSeconds(24L * 3600));
		users.save(user);

		String confirmUrl = apiBase() + "/api/v1/me/email-change/confirm?token="
				+ user.getId() + "." + secret;
		accountMail.sendEmailChangeVerification(user, normalized, confirmUrl);
		audit.event(AuditAction.EMAIL_CHANGE_REQUESTED).actor(user)
				.meta("newEmail", normalized).log();
	}

	/** Confirms a pending email change from the link token. Returns the user. */
	public User confirmEmailChange(String token) {
		User user = resolveToken(token, User::getEmailChangeTokenHash,
				User::getEmailChangeExpiresAt, "error.me.emailChangeInvalid");
		if (user.getPendingEmail() == null) {
			throw ApiException.badRequest("error.me.emailChangeInvalid");
		}
		if (users.existsByEmailIgnoreCase(user.getPendingEmail())) {
			throw ApiException.conflict("error.user.emailInUse");
		}
		user.setEmail(user.getPendingEmail());
		user.setPendingEmail(null);
		user.setEmailVerified(true);
		user.setEmailChangeTokenHash(null);
		user.setEmailChangeExpiresAt(null);
		users.save(user);
		sessions.revokeAll(user.getId());
		accountMail.sendSecurityAlert(user,
				de(user) ? "E-Mail-Adresse geändert" : "Email address changed",
				de(user) ? "Deine Anmelde-E-Mail wurde aktualisiert."
						: "Your sign-in email was updated.");
		audit.event(AuditAction.EMAIL_CHANGED).actor(user).meta("email", user.getEmail()).log();
		return user;
	}

	// --- Password reset -------------------------------------------------------

	public void sendPasswordReset(User user) {
		// Shared with the public forgot-password flow (mints token + emails the
		// in-app reset deep link; no backend form). SSO accounts are rejected there.
		passwordResetService.sendFor(user);
	}

	public void confirmPasswordReset(String token, String newPassword) {
		User user = resolveToken(token, User::getPasswordResetTokenHash,
				User::getPasswordResetExpiresAt, "error.me.passwordResetInvalid");
		userService.validatePassword(newPassword);
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setPasswordChangedAt(Instant.now());
		user.setPasswordResetTokenHash(null);
		user.setPasswordResetExpiresAt(null);
		users.save(user);
		sessions.revokeAll(user.getId());
		accountMail.sendSecurityAlert(user,
				de(user) ? "Passwort geändert" : "Password changed",
				de(user) ? "Dein Passwort wurde zurückgesetzt." : "Your password was reset.");
		audit.event(AuditAction.PASSWORD_RESET_COMPLETED).actor(user).log();
	}

	// --- Sessions -------------------------------------------------------------

	public List<RefreshSession> sessions(String userId) {
		return sessions.list(userId);
	}

	public void revokeSession(String userId, String sessionId, String currentSessionId) {
		if (sessionId.equals(currentSessionId)) {
			throw ApiException.badRequest("error.me.cannotRevokeCurrentSession");
		}
		sessions.revoke(userId, sessionId);
		audit.event(AuditAction.SESSION_REVOKED).actor(userId, null).meta("scope", "single").log();
	}

	public void revokeOtherSessions(String userId, String currentSessionId) {
		sessions.revokeOthers(userId, currentSessionId);
		audit.event(AuditAction.SESSION_REVOKED).actor(userId, null).meta("scope", "others").log();
	}

	// --- Notification preferences --------------------------------------------

	public NotificationPreferences notificationPreferences(User user) {
		NotificationPreferences prefs = user.getNotificationPreferences();
		// Sanitize on read so events added after the prefs were last saved (e.g.
		// "ingest") surface with their defaults instead of being silently absent.
		return (prefs == null ? NotificationPreferences.defaults() : prefs).sanitized();
	}

	public NotificationPreferences saveNotificationPreferences(User user, NotificationPreferences incoming) {
		NotificationPreferences sanitized = incoming.sanitized();
		user.setNotificationPreferences(sanitized);
		users.save(user);
		return sanitized;
	}

	// --- Two-factor (TOTP) ----------------------------------------------------

	public record TotpSetup(String secret, String otpauthUri) {
	}

	public TotpSetup beginTotpSetup(User user) {
		if (user.isTotpEnabled()) {
			throw ApiException.badRequest("error.me.twoFactorAlreadyEnabled");
		}
		String secret = totp.newSecret();
		user.setTotpPendingSecret(secret);
		users.save(user);
		return new TotpSetup(secret, totp.otpauthUri(ISSUER, user.getEmail(), secret));
	}

	/** Verifies the first code, enables 2FA, and returns the one-time recovery codes. */
	public List<String> verifyTotpSetup(User user, String code) {
		String pending = user.getTotpPendingSecret();
		if (pending == null) {
			throw ApiException.badRequest("error.me.twoFactorNoPendingSetup");
		}
		if (!totp.verify(pending, code)) {
			throw ApiException.badRequest("error.me.twoFactorInvalidCode");
		}
		List<String> plain = totp.newRecoveryCodes();
		user.setTotpSecret(pending);
		user.setTotpPendingSecret(null);
		user.setTotpEnabled(true);
		user.setTotpEnabledAt(Instant.now());
		user.setRecoveryCodeHashes(recoveryCodes.hashAll(plain));
		users.save(user);
		accountMail.sendSecurityAlert(user,
				de(user) ? "Zwei-Faktor-Authentifizierung aktiviert" : "Two-factor authentication enabled",
				de(user) ? "2FA wurde für dein Konto aktiviert." : "2FA was enabled on your account.");
		audit.event(AuditAction.TWO_FACTOR_ENABLED).actor(user).log();
		return plain;
	}

	public List<String> regenerateRecoveryCodes(User user, String code) {
		requireEnabled(user);
		if (!totp.verify(user.getTotpSecret(), code) && !recoveryCodes.consume(user, code)) {
			throw ApiException.badRequest("error.me.twoFactorInvalidCode");
		}
		List<String> plain = totp.newRecoveryCodes();
		user.setRecoveryCodeHashes(recoveryCodes.hashAll(plain));
		users.save(user);
		audit.event(AuditAction.RECOVERY_CODES_REGENERATED).actor(user).log();
		return plain;
	}

	public void disableTotp(User user, String code) {
		requireEnabled(user);
		if (!totp.verify(user.getTotpSecret(), code) && !recoveryCodes.consume(user, code)) {
			throw ApiException.badRequest("error.me.twoFactorInvalidCode");
		}
		user.setTotpEnabled(false);
		user.setTotpSecret(null);
		user.setTotpPendingSecret(null);
		user.setTotpEnabledAt(null);
		user.getRecoveryCodeHashes().clear();
		users.save(user);
		accountMail.sendSecurityAlert(user,
				de(user) ? "Zwei-Faktor-Authentifizierung deaktiviert" : "Two-factor authentication disabled",
				de(user) ? "2FA wurde für dein Konto deaktiviert." : "2FA was disabled on your account.");
		audit.event(AuditAction.TWO_FACTOR_DISABLED).actor(user).log();
	}

	// --- Access overview ------------------------------------------------------

	public List<Team> teamsOf(String userId) {
		return teams.findByMembersUserId(userId);
	}

	public List<Project> projectsOf(User user) {
		return projects.visibleTo(user);
	}

	/** The role label a user holds in a project (Lead / Member / Viewer). */
	public String projectRole(Project project, String userId) {
		if (project.getLeadIds() != null && project.getLeadIds().contains(userId)) return "Lead";
		if (project.getMemberIds() != null && project.getMemberIds().contains(userId)) return "Member";
		return "Viewer";
	}

	// --- GDPR -----------------------------------------------------------------

	/** Compiles a machine-readable export of the user's own data (Art. 15). */
	public Map<String, Object> exportData(User user) {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("id", user.getId());
		profile.put("displayName", user.getDisplayName());
		profile.put("username", user.getUsername());
		profile.put("email", user.getEmail());
		profile.put("title", user.getTitle());
		profile.put("locale", user.getLocale());
		profile.put("origin", user.getOrigin().name());
		profile.put("roles", user.getRoles().stream().map(Enum::name).sorted().toList());
		profile.put("createdAt", user.getCreatedAt());
		out.put("profile", profile);
		out.put("notificationPreferences", notificationPreferences(user));
		out.put("sessions", sessions.list(user.getId()).stream().map(s -> Map.of(
				"kind", s.getKind().name(), "os", String.valueOf(s.getOs()),
				"client", String.valueOf(s.getClient()), "location", String.valueOf(s.getLocation()),
				"lastActive", String.valueOf(s.getLastActiveAt()))).toList());
		out.put("teams", teamsOf(user.getId()).stream()
				.map(t -> Map.of("key", t.getKey(), "name", t.getName())).toList());
		out.put("projects", projectsOf(user).stream()
				.map(p -> Map.of("key", p.getKey(), "name", p.getName(),
						"role", projectRole(p, user.getId()))).toList());
		out.put("generatedAt", Instant.now());
		return out;
	}

	@Async
	public void requestDataReport(User user) {
		// The e-mail links to GET /me/export.pdf, which renders the full personal
		// data report (Art. 15) as a PDF on demand. The link carries a short-lived,
		// signed download token so the browser can fetch it without an interactive
		// session — replacing the old link to the bearer-only JSON endpoint, which
		// browsers could not authenticate and so downloaded an empty file.
		String token = tokens.issueDownloadToken(user,
				com.ahmadre.hinata.auth.TokenService.PURPOSE_DATA_EXPORT, EXPORT_TOKEN_TTL_SECONDS);
		String downloadUrl = apiBase() + "/api/v1/me/export.pdf?token="
				+ java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
		accountMail.sendDataReportReady(user, downloadUrl);
		audit.event(AuditAction.DATA_EXPORT_REQUESTED).actor(user).log();
		log.info("Data report prepared for user {}", user.getId());
	}

	/** Erases the account (Art. 17). Guards the last-active-admin invariant. */
	public void deleteAccount(User user) {
		if (user.isAdmin()
				&& users.countByRolesContainingAndActiveIsTrueAndIdNot(Role.ADMIN, user.getId()) == 0) {
			throw ApiException.conflict("error.user.cannotDeleteLastAdmin");
		}
		sessions.revokeAll(user.getId());
		// Mail the user before their account (and its notifications) are removed.
		notifications.notifyAccountDeleted(user);
		audit.event(AuditAction.ACCOUNT_DELETED).actor(user)
				.meta("self", "true").meta("email", user.getEmail()).log();
		userService.delete(user);
	}

	// --- helpers --------------------------------------------------------------

	private void requireEnabled(User user) {
		if (!user.isTotpEnabled()) {
			throw ApiException.badRequest("error.me.twoFactorNotEnabled");
		}
	}

	private interface HashGetter {
		String hash(User user);
	}

	private interface ExpiryGetter {
		Instant expiry(User user);
	}

	/** token = "{userId}.{secret}"; matches the stored bcrypt hash + expiry. */
	private User resolveToken(String token, HashGetter hash, ExpiryGetter expiry, String errorKey) {
		if (token == null || !token.contains(".")) {
			throw ApiException.badRequest(errorKey);
		}
		int dot = token.indexOf('.');
		String userId = token.substring(0, dot);
		String secret = token.substring(dot + 1);
		User user = users.findById(userId).orElseThrow(() -> ApiException.badRequest(errorKey));
		String storedHash = hash.hash(user);
		Instant expiresAt = expiry.expiry(user);
		if (storedHash == null || expiresAt == null || expiresAt.isBefore(Instant.now())
				|| !passwordEncoder.matches(secret, storedHash)) {
			throw ApiException.badRequest(errorKey);
		}
		return user;
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String apiBase() {
		String base = properties.getBaseUrl();
		return base == null ? "" : base.replaceAll("/+$", "");
	}

	private boolean de(User user) {
		return "de".equalsIgnoreCase(user.getLocale());
	}
}
