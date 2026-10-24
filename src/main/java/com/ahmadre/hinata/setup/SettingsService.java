package com.ahmadre.hinata.setup;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Read/write access to the {@link ServerSettings} singleton. Writes publish a
 * {@link SettingsChangedEvent} so SSO registries can refresh without restart.
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

	private final MongoTemplate mongo;
	private final ApplicationEventPublisher events;

	public record SettingsChangedEvent(ServerSettings settings) {
	}

	public ServerSettings get() {
		ServerSettings settings = mongo.findById(ServerSettings.SINGLETON_ID, ServerSettings.class);
		return settings != null ? settings : new ServerSettings();
	}

	/**
	 * The language of the instance: what words written for everybody at once are in, such as the
	 * placeholder of content moderation removed (HIN-169). German when none is set.
	 */
	public Locale instanceLocale() {
		ServerSettings.General general = get().getGeneral();
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
		events.publishEvent(new SettingsChangedEvent(saved));
		return saved;
	}
}
