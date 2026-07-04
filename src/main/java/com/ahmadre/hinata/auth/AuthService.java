package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.sso.LdapAuthenticator;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.RecoveryCodeService;
import com.ahmadre.hinata.me.RefreshSession;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.me.TotpService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
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
	private final SessionService sessions;
	private final TotpService totp;
	private final RecoveryCodeService recoveryCodes;
	private final AuditService audit;
	private final AuthPolicy authPolicy;
	private final org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

	public record LoginResult(User user, TokenService.TokenPair tokens) {
	}

	/**
	 * Outcome of a credential check: either a full token pair, or — when the
	 * account has 2FA enabled — a short-lived challenge the client must complete
	 * with {@link #completeMfa} before a real session is minted.
	 */
	public record AuthOutcome(User user, TokenService.TokenPair tokens, String mfaToken) {
		public boolean mfaRequired() {
			return mfaToken != null;
		}
	}

	/** Local credentials first, then LDAP fallback when enabled. */
	public AuthOutcome login(String identifier, String password, String ip, String userAgent) {
		try {
			attempts.assertNotBlocked(identifier, ip);
		}
		catch (ApiException blocked) {
			audit.event(AuditAction.LOGIN_BLOCKED).actor(null, identifier)
					.ip(ip).userAgent(userAgent).log();
			throw blocked;
		}

		Optional<User> resolved = localLogin(identifier, password)
				.or(() -> ldapLogin(identifier, password));

		User user = resolved.orElseThrow(() -> {
			attempts.recordFailure(identifier, ip);
			audit.event(AuditAction.LOGIN_FAILURE).actor(null, identifier)
					.ip(ip).userAgent(userAgent).meta("reason", "invalidCredentials").log();
			return ApiException.unauthorized("error.auth.invalidCredentials");
		});
		// Self-registered locals: give a precise reason. Scoped to LOCAL origin so
		// an SSO/LDAP account is never gated on email verification; and since
		// emailVerified defaults true for invited/admin-created accounts, this only
		// blocks a genuine unverified sign-up.
		if (user.getOrigin() == User.Origin.LOCAL && !user.isEmailVerified()) {
			audit.event(AuditAction.LOGIN_FAILURE).actor(user)
					.ip(ip).userAgent(userAgent).meta("reason", "emailNotVerified").log();
			throw ApiException.forbidden("error.auth.emailNotVerified");
		}
		if (user.isAwaitingApproval()) {
			audit.event(AuditAction.LOGIN_FAILURE).actor(user)
					.ip(ip).userAgent(userAgent).meta("reason", "pendingApproval").log();
			throw ApiException.forbidden("error.auth.pendingApproval");
		}
		if (!user.isActive()) {
			audit.event(AuditAction.LOGIN_FAILURE).actor(user)
					.ip(ip).userAgent(userAgent).meta("reason", "accountDeactivated").log();
			throw ApiException.forbidden("error.auth.accountDeactivated");
		}
		attempts.recordSuccess(identifier, ip);
		if (user.isTotpEnabled()) {
			// Credentials are valid but the session is only minted once the 2FA
			// challenge is completed — LOGIN_SUCCESS is recorded there.
			return new AuthOutcome(user, null, tokens.issueMfaChallenge(user));
		}
		AuthOutcome outcome = new AuthOutcome(user, issueWithSession(user, ip, userAgent), null);
		audit.event(AuditAction.LOGIN_SUCCESS).actor(user).ip(ip).userAgent(userAgent).log();
		return outcome;
	}

	/** Completes a 2FA login: verifies the TOTP (or a recovery code) + mints a session. */
	public LoginResult completeMfa(String mfaToken, String code, String ip, String userAgent) {
		org.springframework.security.oauth2.jwt.Jwt jwt;
		try {
			jwt = jwtDecoder.decode(mfaToken);
		}
		catch (org.springframework.security.oauth2.jwt.JwtException ex) {
			throw ApiException.unauthorized("error.auth.invalidMfaToken");
		}
		if (!TokenService.isMfaToken(jwt)) {
			throw ApiException.unauthorized("error.auth.invalidMfaToken");
		}
		User user = users.findById(jwt.getSubject())
				.filter(User::isActive)
				.orElseThrow(() -> ApiException.unauthorized("error.auth.unknownUser"));
		boolean ok = totp.verify(user.getTotpSecret(), code)
				|| recoveryCodes.consume(user, code);
		if (!ok) {
			audit.event(AuditAction.MFA_FAILURE).actor(user).ip(ip).userAgent(userAgent).log();
			throw ApiException.unauthorized("error.auth.invalidTwoFactorCode");
		}
		LoginResult result = new LoginResult(user, issueWithSession(user, ip, userAgent));
		audit.event(AuditAction.LOGIN_SUCCESS).actor(user).ip(ip).userAgent(userAgent)
				.meta("mfa", "totp").log();
		return result;
	}

	/** Opens a tracked session and issues tokens carrying its id. */
	public TokenService.TokenPair issueWithSession(User user, String ip, String userAgent) {
		RefreshSession session = sessions.start(user, ip, userAgent);
		return tokens.issue(user, session.getId());
	}

	private Optional<User> localLogin(String identifier, String password) {
		// When local auth is disabled (SSO-only mode), skip password matching
		// entirely; LDAP/OIDC/SAML sign-in stays available.
		if (!authPolicy.localAuthEnabled()) {
			return Optional.empty();
		}
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

	/**
	 * Rotates a token pair. When the refresh token carries a {@code sid}, the
	 * session must still exist (i.e. not have been revoked from another device);
	 * its last-active timestamp is bumped. Session-less (legacy) tokens are
	 * grandfathered so an in-flight client isn't logged out by a deploy.
	 */
	public TokenService.TokenPair refresh(User user, String sessionId) {
		if (!user.isActive()) {
			throw ApiException.forbidden("error.auth.accountDeactivated");
		}
		if (sessionId != null) {
			if (!sessions.isActive(sessionId)) {
				throw ApiException.unauthorized("error.auth.sessionRevoked");
			}
			sessions.touch(sessionId);
			return tokens.issue(user, sessionId);
		}
		return tokens.issue(user);
	}
}
