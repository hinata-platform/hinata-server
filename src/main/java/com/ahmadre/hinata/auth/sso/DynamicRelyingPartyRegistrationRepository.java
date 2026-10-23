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
 *
 * <p>A change of settings rebuilds it, and the registration in use stays until the new one is
 * built: a sign-in completing meanwhile must still find the identity provider it came from, or it
 * would be told of none and fail. A failed rebuild keeps it too while the metadata address and
 * entity id it came from are what the settings still say.
 */
@Slf4j
@Component
public class DynamicRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

	public static final String SAML_ID = "saml";

	private final SettingsService settings;
	private final AtomicReference<Built> cached = new AtomicReference<>();
	private final AtomicBoolean dirty = new AtomicBoolean(true);

	/** A registration and the settings it was built from. */
	private record Built(String source, RelyingPartyRegistration registration) {
	}

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
		Built built = cached.get();
		return built == null ? null : built.registration();
	}

	private void rebuild() {
		ServerSettings.Saml saml = settings.get().getSaml();
		if (!saml.isEnabled() || saml.getIdpMetadataUri() == null || saml.getIdpMetadataUri().isBlank()) {
			cached.set(null);
			return;
		}
		String source = saml.getIdpMetadataUri() + "\n" + saml.getEntityId();
		try {
			RelyingPartyRegistration.Builder builder = RelyingPartyRegistrations
					.fromMetadataLocation(saml.getIdpMetadataUri())
					.registrationId(SAML_ID);
			if (saml.getEntityId() != null && !saml.getEntityId().isBlank()) {
				builder.entityId(saml.getEntityId());
			}
			cached.set(new Built(source, builder.build()));
		}
		catch (Exception ex) {
			log.error("Loading SAML IdP metadata failed: {}", ex.getMessage());
			Built previous = cached.get();
			if (previous != null && !previous.source().equals(source)) {
				cached.set(null);
			}
		}
	}
}
