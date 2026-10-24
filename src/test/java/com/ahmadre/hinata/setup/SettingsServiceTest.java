package com.ahmadre.hinata.setup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What every request reads of the settings is one copy at most thirty seconds old: a save on
 * this instance shows at once, one on another instance, which tells this one nothing, within
 * thirty seconds.
 */
class SettingsServiceTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-24T08:00:00Z"));
	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private ServerSettings stored;
	private SettingsService settings;

	@BeforeEach
	void setUp() {
		stored = named("Erst");
		when(mongo.findById(ServerSettings.SINGLETON_ID, ServerSettings.class)).thenAnswer(invocation -> stored);
		when(mongo.save(any(ServerSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Clock clock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		settings = new SettingsService(mongo, mock(ApplicationEventPublisher.class), clock);
	}

	@Test
	void anotherInstancesSaveShowsWithinThirtySeconds() {
		assertThat(name(settings.current())).isEqualTo("Erst");
		stored = named("Dann");

		now.set(now.get().plusSeconds(29));
		assertThat(name(settings.current())).isEqualTo("Erst");
		now.set(now.get().plusSeconds(1));
		assertThat(name(settings.current())).isEqualTo("Dann");
		verify(mongo, times(2)).findById(ServerSettings.SINGLETON_ID, ServerSettings.class);
	}

	@Test
	void aSaveHereShowsAtOnce() {
		assertThat(name(settings.current())).isEqualTo("Erst");

		settings.save(named("Neu"));

		assertThat(name(settings.current())).isEqualTo("Neu");
		assertThat(name(settings.get())).as("read as stored, for changing it").isEqualTo("Erst");
	}

	private static ServerSettings named(String operator) {
		ServerSettings settings = new ServerSettings();
		ServerSettings.Moderation moderation = new ServerSettings.Moderation();
		moderation.setOperatorName(operator);
		settings.setModeration(moderation);
		return settings;
	}

	private static String name(ServerSettings settings) {
		return settings.getModeration().getOperatorName();
	}
}
