package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The operator's published contact (HIN-26, HIN-167): the stored one wins over the
 * environment, a missing one is said as such, and a change another instance saved shows here
 * within thirty seconds although no event reaches this one.
 */
class OperatorContactTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-24T08:00:00Z"));
	private ServerSettings stored;
	private HinataProperties properties;
	private OperatorContact contact;

	@BeforeEach
	void setUp() {
		stored = new ServerSettings();
		properties = new HinataProperties();
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenAnswer(invocation -> stored);
		Clock clock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		contact = new OperatorContact(settings, properties, clock);
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
		contact.onSettingsChanged(new SettingsService.SettingsChangedEvent(stored));

		assertThat(contact.current())
				.isEqualTo(new OperatorContact.Contact("Verein Beispiel e. V.", "meldungen@example.org",
						"https://example.org/impressum"));
		assertThat(contact.current().missing()).isFalse();
	}

	@Test
	@DisplayName("a change saved on another instance shows here within thirty seconds")
	void anotherInstancesChangeArrivesWithinThirtySeconds() {
		assertThat(contact.current().missing()).isTrue();
		// Saved elsewhere: no event reaches this instance.
		stored = withContact(null, "meldungen@example.org", null);

		now.set(now.get().plusSeconds(29));
		assertThat(contact.current().missing()).isTrue();
		now.set(now.get().plusSeconds(1));
		assertThat(contact.current().contactEmail()).isEqualTo("meldungen@example.org");
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
