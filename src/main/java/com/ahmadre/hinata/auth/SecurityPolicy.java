package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the runtime security policy — minimum password
 * length, brute-force lockout thresholds, session (refresh-token) lifetime and
 * the per-IP rate-limit switch. Each value is resolved DB-over-env: a non-null
 * override stored on {@link ServerSettings.Security} by an admin (via the in-app
 * "Security" panel) wins, otherwise the env-driven {@link HinataProperties}
 * default. Every enforcement point ({@code UserService#validatePassword},
 * {@code LoginAttemptService}, {@code RateLimitFilter}, {@code TokenService}) and
 * {@code MetaController} reads through here, so the admin panel is the actual
 * source of truth and nothing downstream hardcodes these values.
 *
 * <p>The resolved {@link ServerSettings.Security} block is cached and refreshed on
 * the {@link SettingsService.SettingsChangedEvent} (the same in-process refresh
 * mechanism the SSO registries and SMTP sender use), so hot paths like the
 * per-request rate-limit filter never hit Mongo.
 */
@Component
@RequiredArgsConstructor
public class SecurityPolicy {

	/** Absolute floor for the effective minimum password length (defence in depth). */
	public static final int PASSWORD_MIN_FLOOR = 8;

	private final SettingsService settings;
	private final HinataProperties properties;

	private volatile ServerSettings.Security cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = event.settings().getSecurity();
	}

	/** Effective minimum password length (DB override, else env default), never below the floor. */
	public int passwordMinLength() {
		Integer override = db().getPasswordMinLength();
		int value = override != null ? override : properties.getSecurity().getPasswordMinLength();
		return Math.max(PASSWORD_MIN_FLOOR, value);
	}

	/** Effective number of failed logins per account before a temporary block. */
	public int maxLoginAttempts() {
		Integer override = db().getMaxLoginAttempts();
		return override != null ? override : properties.getRateLimit().getMaxLoginFailures();
	}

	/** Effective minutes an account/IP stays blocked after too many failures. */
	public int lockoutMinutes() {
		Integer override = db().getLockoutMinutes();
		return override != null ? override : properties.getRateLimit().getLoginBlockMinutes();
	}

	/** Effective session (refresh-token) lifetime in hours. */
	public int sessionLifetimeHours() {
		Integer override = db().getSessionLifetimeHours();
		if (override != null) return override;
		return (int) (properties.getJwt().getRefreshTokenSeconds() / 3600L);
	}

	/** Effective session (refresh-token) lifetime in seconds, for token minting. */
	public long sessionLifetimeSeconds() {
		return (long) sessionLifetimeHours() * 3600L;
	}

	/** Whether per-IP API rate limiting is active. */
	public boolean rateLimitEnabled() {
		Boolean override = db().getRateLimitEnabled();
		return override != null ? override : properties.getRateLimit().isEnabled();
	}

	private ServerSettings.Security db() {
		ServerSettings.Security c = cached;
		if (c == null) {
			c = settings.get().getSecurity();
			cached = c;
		}
		return c != null ? c : new ServerSettings.Security();
	}
}
