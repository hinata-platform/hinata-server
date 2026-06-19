package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SAML relying party built from IdP metadata configured in the admin area
 * (e.g. Synology SSO Server metadata URL).
 */
@Slf4j
@Component
public class DynamicRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

	public static final String SAML_ID = "saml";

	private final SettingsService settings;
	private final AtomicReference<RelyingPartyRegistration> cached = new AtomicReference<>();
	private final AtomicBoolean dirty = new AtomicBoolean(true);

	public DynamicRelyingPartyRegistrationRepository(SettingsService settings) {
		this.settings = settings;
	}

	@EventListener
	public void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		dirty.set(true);
	}

	@Override
	public RelyingPartyRegistration findByRegistrationId(String registrationId) {
		if (!SAML_ID.equals(registrationId)) {
			return null;
		}
		if (dirty.compareAndSet(true, false)) {
			rebuild();
		}
		return cached.get();
	}

	private void rebuild() {
		cached.set(null);
		ServerSettings.Saml saml = settings.get().getSaml();
		if (!saml.isEnabled() || saml.getIdpMetadataUri() == null || saml.getIdpMetadataUri().isBlank()) {
			return;
		}
		try {
			RelyingPartyRegistration.Builder builder = RelyingPartyRegistrations
					.fromMetadataLocation(saml.getIdpMetadataUri())
					.registrationId(SAML_ID);
			if (saml.getEntityId() != null && !saml.getEntityId().isBlank()) {
				builder.entityId(saml.getEntityId());
			}
			cached.set(builder.build());
		}
		catch (Exception ex) {
			log.error("Loading SAML IdP metadata failed: {}", ex.getMessage());
		}
	}
}
