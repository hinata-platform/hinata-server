package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the local-authentication policy flags. Each flag is
 * resolved DB-over-env: a non-null value on {@link ServerSettings.App} (set by an
 * admin) wins, otherwise the env default from {@link HinataProperties.App}. Used
 * by the login, registration and password-reset flows to gate behaviour and by
 * {@code MetaController} to advertise the effective policy to clients.
 */
@Component
@RequiredArgsConstructor
public class AuthPolicy {

	private final SettingsService settings;
	private final HinataProperties properties;

	/** Local email/password auth (sign-in, sign-up, forgot-password) is enabled. */
	public boolean localAuthEnabled() {
		Boolean override = app().getLocalAuthEnabled();
		return override != null ? override : properties.getApp().isLocalAuthEnabled();
	}

	/** Public self-registration is open (only meaningful when local auth is on). */
	public boolean registrationEnabled() {
		Boolean override = app().getRegistrationEnabled();
		return override != null ? override : properties.getApp().isRegistrationEnabled();
	}

	/** A verified self-registration must be approved by an admin before sign-in. */
	public boolean requireAdminApproval() {
		Boolean override = app().getRequireAdminApproval();
		return override != null ? override : properties.getApp().isRequireAdminApproval();
	}

	public void assertLocalAuthEnabled() {
		if (!localAuthEnabled()) {
			throw ApiException.forbidden("error.auth.localAuthDisabled");
		}
	}

	public void assertRegistrationEnabled() {
		assertLocalAuthEnabled();
		if (!registrationEnabled()) {
			throw ApiException.forbidden("error.auth.registrationDisabled");
		}
	}

	private ServerSettings.App app() {
		return settings.get().getApp();
	}
}
