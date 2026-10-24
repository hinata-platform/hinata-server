package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The operator's published contact (HIN-26, HIN-167): the stored one wins over the
 * environment, and a missing one is said as such. How late another instance's change shows is
 * the settings' own business ({@link SettingsServiceTest}).
 */
class OperatorContactTest {

	private ServerSettings stored;
	private HinataProperties properties;
	private OperatorContact contact;

	@BeforeEach
	void setUp() {
		stored = new ServerSettings();
		properties = new HinataProperties();
		SettingsService settings = mock(SettingsService.class);
		when(settings.current()).thenAnswer(invocation -> stored);
		contact = new OperatorContact(settings, properties);
	}

	@Test
	@DisplayName("without a stored or configured mailbox the contact is missing, not made up")
	void aMissingContactIsSaidAsSuch() {
		assertThat(contact.current().missing()).isTrue();
		assertThat(contact.current().contactEmail()).isNull();
	}

	@Test
	@DisplayName("the environment stands in until an administrator stores a contact, which then wins")
	void theStoredContactWinsOverTheEnvironment() {
		properties.getModeration().setContactEmail("abuse@env.example.org");
		assertThat(contact.current().contactEmail()).isEqualTo("abuse@env.example.org");

		stored = withContact("Verein Beispiel e. V.", " meldungen@example.org ", "https://example.org/impressum");

		assertThat(contact.current())
				.isEqualTo(new OperatorContact.Contact("Verein Beispiel e. V.", "meldungen@example.org",
						"https://example.org/impressum"));
		assertThat(contact.current().missing()).isFalse();
	}

	private static ServerSettings withContact(String operator, String email, String imprint) {
		ServerSettings settings = new ServerSettings();
		ServerSettings.Moderation moderation = new ServerSettings.Moderation();
		moderation.setOperatorName(operator);
		moderation.setContactEmail(email);
		moderation.setImprintUrl(imprint);
		settings.setModeration(moderation);
		return settings;
	}
}
