package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SAML registration an SSO sign-in reads its provider's entity id from (HIN-179). Whatever
 * names the provider must not come and go with a change of settings: a sign-in that found none
 * would be told of another provider and bind its account anew by the address.
 */
class DynamicRelyingPartyRegistrationRepositoryTest {

	private static final String ENTITY = "https://idp.example.org/saml";

	@TempDir
	Path folder;

	private ServerSettings current;
	private DynamicRelyingPartyRegistrationRepository repository;
	private Path metadata;

	@BeforeEach
	void setUp() throws IOException {
		metadata = folder.resolve("idp-metadata.xml");
		try (InputStream source = getClass().getResourceAsStream("/saml/idp-metadata.xml")) {
			Files.write(metadata, source.readAllBytes());
		}
		current = new ServerSettings();
		current.getSaml().setEnabled(true);
		current.getSaml().setIdpMetadataUri(metadata.toUri().toString());
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenAnswer(invocation -> current);
		repository = new DynamicRelyingPartyRegistrationRepository(settings);
	}

	@Test
	@DisplayName("names the provider by the entity id in its metadata")
	void namesTheProviderByItsEntityId() {
		assertThat(entityId()).isEqualTo(ENTITY);
	}

	@Test
	@DisplayName("a failed reload of the same metadata keeps the registration in use")
	void aFailedReloadOfTheSameMetadataKeepsTheRegistration() throws IOException {
		assertThat(entityId()).isEqualTo(ENTITY);
		// The provider answers with garbage for a moment while some other setting is saved.
		Files.writeString(metadata, "not metadata");
		settingsSaved();

		assertThat(entityId()).isEqualTo(ENTITY);
	}

	@Test
	@DisplayName("metadata from another address that does not load leaves no registration")
	void anotherAddressThatFailsLeavesNone() {
		assertThat(entityId()).isEqualTo(ENTITY);
		current.getSaml().setIdpMetadataUri(folder.resolve("missing.xml").toUri().toString());
		settingsSaved();

		assertThat(repository.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.SAML_ID)).isNull();
	}

	@Test
	@DisplayName("switched off, SAML has no registration")
	void switchedOffThereIsNone() {
		assertThat(entityId()).isEqualTo(ENTITY);
		current.getSaml().setEnabled(false);
		settingsSaved();

		assertThat(repository.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.SAML_ID)).isNull();
	}

	private String entityId() {
		return repository.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.SAML_ID)
				.getAssertingPartyMetadata().getEntityId();
	}

	private void settingsSaved() {
		repository.onSettingsChanged(new SettingsService.SettingsChangedEvent(current));
	}
}
