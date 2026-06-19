package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OAuth2/OIDC client registrations sourced from the admin-managed
 * {@link ServerSettings} instead of static application properties.
 * Refreshes lazily after every settings change – no restart required.
 */
@Slf4j
@Component
public class DynamicClientRegistrationRepository implements ClientRegistrationRepository {

	public static final String OIDC_ID = "oidc";
	public static final String OAUTH2_ID = "oauth2";

	private final SettingsService settings;
	private final HinataProperties properties;
	private final Map<String, ClientRegistration> cache = new ConcurrentHashMap<>();
	private final AtomicBoolean dirty = new AtomicBoolean(true);

	public DynamicClientRegistrationRepository(SettingsService settings, HinataProperties properties) {
		this.settings = settings;
		this.properties = properties;
	}

	/**
	 * Absolute redirect URI derived from the configured public base URL. Never
	 * guessed from the incoming request: behind tunnels/proxies (ngrok, Traefik)
	 * the request scheme is unreliable, and the IdP rejects any mismatch with
	 * the registered redirect URI.
	 */
	private String redirectUri(String registrationId) {
		String base = properties.getBaseUrl();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/login/oauth2/code/" + registrationId;
	}

	@EventListener
	public void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		dirty.set(true);
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		if (dirty.compareAndSet(true, false)) {
			rebuild();
		}
		return cache.get(registrationId);
	}

	private void rebuild() {
		cache.clear();
		ServerSettings current = settings.get();
		ServerSettings.Oidc oidc = current.getOidc();
		if (oidc.isEnabled() && notBlank(oidc.getIssuerUri()) && notBlank(oidc.getClientId())) {
			try {
				cache.put(OIDC_ID, ClientRegistrations.fromIssuerLocation(oidc.getIssuerUri())
						.registrationId(OIDC_ID)
						.clientId(oidc.getClientId())
						.clientSecret(oidc.getClientSecret())
						.redirectUri(redirectUri(OIDC_ID))
						.scope(oidc.getScopes().split("\\s*,\\s*"))
						.build());
			}
			catch (Exception ex) {
				log.error("OIDC discovery failed for {}: {}", oidc.getIssuerUri(), ex.getMessage());
			}
		}
		ServerSettings.OAuth2 oauth2 = current.getOauth2();
		if (oauth2.isEnabled() && notBlank(oauth2.getClientId()) && notBlank(oauth2.getTokenUri())) {
			cache.put(OAUTH2_ID, ClientRegistration.withRegistrationId(OAUTH2_ID)
					.clientId(oauth2.getClientId())
					.clientSecret(oauth2.getClientSecret())
					.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
					.redirectUri(redirectUri(OAUTH2_ID))
					.authorizationUri(oauth2.getAuthorizationUri())
					.tokenUri(oauth2.getTokenUri())
					.userInfoUri(oauth2.getUserInfoUri())
					.userNameAttributeName(oauth2.getUserNameAttribute())
					.scope(oauth2.getScopes().split("\\s*,\\s*"))
					.clientName(oauth2.getDisplayName())
					.build());
		}
	}

	private boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
