package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.common.Expiring;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Read/write access to the {@link ServerSettings} singleton. Writes publish a
 * {@link SettingsChangedEvent} so SSO registries can refresh without restart.
 *
 * <p>What every request reads comes from {@link #current()}, a copy at most {@link #MAX_AGE} old:
 * a save tells only the instance that made it, so another instance sees it that late.
 */
@Service
public class SettingsService {

	static final Duration MAX_AGE = Duration.ofSeconds(30);

	private final MongoTemplate mongo;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final Expiring<ServerSettings> current;

	public SettingsService(MongoTemplate mongo, ApplicationEventPublisher events, Clock clock) {
		this.mongo = mongo;
		this.events = events;
		this.clock = clock;
		this.current = new Expiring<>(MAX_AGE, this::get);
	}

	public record SettingsChangedEvent(ServerSettings settings) {
	}

	/** The settings as stored, read now: for changing them and saving them again. */
	public ServerSettings get() {
		ServerSettings settings = mongo.findById(ServerSettings.SINGLETON_ID, ServerSettings.class);
		return settings != null ? settings : new ServerSettings();
	}

	/**
	 * The settings as they stand, at most {@link #MAX_AGE} old: for what every request reads, an
	 * upload, {@code /meta}, a delete. Shared between requests, so read it and never change it; a
	 * change goes through {@link #get()} and {@link #save}.
	 */
	public ServerSettings current() {
		return current.get(clock.instant());
	}

	/**
	 * The language of the instance: what words written for everybody at once are in, such as the
	 * placeholder of content moderation removed (HIN-169). German when none is set.
	 */
	public Locale instanceLocale() {
		ServerSettings.General general = current().getGeneral();
		String tag = general == null ? null : general.getDefaultLocale();
		if (tag == null || tag.isBlank()) {
			return Locale.GERMAN;
		}
		Locale locale = Locale.forLanguageTag(tag);
		return locale.getLanguage().isEmpty() ? Locale.GERMAN : locale;
	}

	public ServerSettings save(ServerSettings settings) {
		settings.setId(ServerSettings.SINGLETON_ID);
		ServerSettings saved = mongo.save(settings);
		current.set(saved, clock.instant());
		events.publishEvent(new SettingsChangedEvent(saved));
		return saved;
	}
}
