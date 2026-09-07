package com.ahmadre.hinata.common;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The merge rule that used to exist three times over. What it has to get right
 * is the thing all three copies were written to get right: an admin who turns
 * <em>one</em> flag off must not thereby freeze the whole flag set, because the
 * next release ships a new flag whose default nobody would ever see.
 */
class FeatureFlagsTest {

	private final HinataProperties properties = new HinataProperties();
	private final SettingsService settings = mock(SettingsService.class);

	private FeatureFlags flags(FeatureFlags.Module... modules) {
		return new FeatureFlags(settings, properties, List.of(modules));
	}

	private void envDefaults(Map<String, Boolean> defaults) {
		properties.getApp().setFeatureFlags(defaults);
	}

	private void stored(Map<String, Boolean> overrides) {
		ServerSettings stored = new ServerSettings();
		stored.getApp().setFeatureFlags(overrides == null ? null : new LinkedHashMap<>(overrides));
		when(settings.get()).thenReturn(stored);
	}

	private static FeatureFlags.Module module(String key, boolean enabled) {
		return new FeatureFlags.Module() {
			@Override
			public String flagKey() {
				return key;
			}

			@Override
			public boolean flagEnabled() {
				return enabled;
			}
		};
	}

	@Test
	void storedOverrideOfOneKeyLeavesTheOtherEnvDefaultsStanding() {
		envDefaults(Map.of("emailReply", true, "freshlyShipped", true));
		stored(Map.of("emailReply", false));

		Map<String, Boolean> effective = flags().effective();

		assertThat(effective).containsEntry("emailReply", false);
		// The whole point: "DB wins wholesale" would have dropped this one.
		assertThat(effective).containsEntry("freshlyShipped", true);
	}

	@Test
	void noStoredOverridesAtAllYieldsTheEnvDefaults() {
		envDefaults(Map.of("emailReply", true));
		stored(null);

		assertThat(flags().effective()).containsExactly(Map.entry("emailReply", true));
	}

	@Test
	void moduleFlagsAreLayeredOnTopAndWinOverAStoredEntryOfTheSameName() {
		envDefaults(Map.of("emailReply", true));
		// An admin who somehow typed the module's key into the free-form map does
		// not get to contradict the module's own switch.
		stored(Map.of("mcp", true));

		assertThat(flags(module("mcp", false)).effective())
				.containsEntry("mcp", false)
				.containsEntry("emailReply", true);
	}

	@Test
	void theEditableMapNeverContainsAModuleFlag() {
		envDefaults(Map.of("emailReply", true));
		stored(Map.of());

		FeatureFlags featureFlags = flags(module("mcp", true),
				module("advanced_time_tracking", true));

		assertThat(featureFlags.configurable()).containsOnlyKeys("emailReply");
		assertThat(featureFlags.effective())
				.containsOnlyKeys("emailReply", "mcp", "advanced_time_tracking");
	}

	@Test
	void askingAboutOneFlagAgreesWithTheWholeMap() {
		envDefaults(Map.of("emailReply", true));
		stored(Map.of("mcp", true));
		FeatureFlags featureFlags = flags(module("mcp", false));

		// enabled() short-circuits on the module rather than building the map, so
		// this is the assertion that keeps the shortcut honest.
		assertThat(featureFlags.enabled("mcp")).isFalse();
		assertThat(featureFlags.enabled("emailReply")).isTrue();
		featureFlags.effective().forEach((key, value) ->
				assertThat(featureFlags.enabled(key)).as(key).isEqualTo(value));
	}

	@Test
	void anUnknownFlagIsOffRatherThanNull() {
		envDefaults(Map.of());
		stored(Map.of());

		assertThat(flags().enabled("nothing_like_this")).isFalse();
	}
}
