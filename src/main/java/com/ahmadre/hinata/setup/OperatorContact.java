package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.config.HinataProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Who runs this instance and where reports and questions about moderation go (HIN-26): the
 * operator, the mailbox and the imprint, which the app shows to everybody and the stores and
 * the DSA (Art. 11, 12) ask to be published. In the core, so {@code /api/v1/meta} and the mails
 * read it without reaching into the moderation module.
 *
 * <p>The stored contact wins over {@code hinata.moderation.contact-email}. There is no stand-in
 * beyond that: a no-reply address of the mail relay published as the contact point would be
 * worse than saying none is set, which {@link Contact#missing()} does.
 *
 * <p>Cached, and read again once the copy is older than {@link #MAX_AGE}: a settings change
 * tells only the instance that saved it, and with two instances the other one would otherwise
 * show the old contact until a restart.
 */
@Component
@RequiredArgsConstructor
public class OperatorContact {

	static final Duration MAX_AGE = Duration.ofSeconds(30);

	private final SettingsService settings;
	private final HinataProperties properties;
	private final Clock clock;

	private volatile Snapshot cached;

	/** The published contact. Every field may be null; {@link #missing()} says the one that matters is. */
	public record Contact(String operatorName, String contactEmail, String imprintUrl) {

		/** Whether there is no mailbox to publish. */
		public boolean missing() {
			return contactEmail == null;
		}
	}

	private record Snapshot(Contact contact, Instant readAt) {
	}

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = new Snapshot(from(event.settings()), clock.instant());
	}

	/** The contact as it stands, at most {@link #MAX_AGE} old. */
	public Contact current() {
		Snapshot snapshot = cached;
		Instant now = clock.instant();
		if (snapshot == null || !snapshot.readAt().plus(MAX_AGE).isAfter(now)) {
			snapshot = new Snapshot(from(settings.get()), now);
			cached = snapshot;
		}
		return snapshot.contact();
	}

	/**
	 * The contact [stored] settings publish, read now: for a reader that must not wait for the
	 * cache, such as the house rules, which become a new version the moment the contact changes.
	 */
	public Contact of(ServerSettings stored) {
		return from(stored);
	}

	private Contact from(ServerSettings stored) {
		ServerSettings.Moderation block = stored.getModeration();
		String email = block == null ? null : trimToNull(block.getContactEmail());
		return new Contact(block == null ? null : trimToNull(block.getOperatorName()),
				email != null ? email : trimToNull(properties.getModeration().getContactEmail()),
				block == null ? null : trimToNull(block.getImprintUrl()));
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
