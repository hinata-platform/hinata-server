package com.ahmadre.hinata.setup;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

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

	public ServerSettings save(ServerSettings settings) {
		settings.setId(ServerSettings.SINGLETON_ID);
		ServerSettings saved = mongo.save(settings);
		events.publishEvent(new SettingsChangedEvent(saved));
		return saved;
	}
}
