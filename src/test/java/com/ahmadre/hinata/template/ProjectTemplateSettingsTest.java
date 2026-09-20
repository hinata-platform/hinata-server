package com.ahmadre.hinata.template;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolution rule for the one switch this module has: a stored value wins, null defers to the
 * environment, and the answer changes without a restart when an administrator saves.
 *
 * <p>The cache is part of the contract rather than an optimisation: this resolver sits on the path
 * of every request to the module, and a read of Mongo per request is what {@code McpSettings} does
 * and what this deliberately does not.
 */
class ProjectTemplateSettingsTest {

	private final SettingsService settings = mock(SettingsService.class);
	private final HinataProperties properties = new HinataProperties();
	private final ProjectTemplateSettings resolver =
			new ProjectTemplateSettings(settings, properties);

	private void env(boolean enabled) {
		properties.getProjectTemplates().setEnabled(enabled);
	}

	/** Stores a block; null stands for "this instance never saved one". */
	private ServerSettings stored(Boolean override) {
		ServerSettings document = new ServerSettings();
		if (override != null) {
			ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
			block.setEnabled(override);
			document.setProjectTemplates(block);
		}
		when(settings.get()).thenReturn(document);
		return document;
	}

	@Test
	@DisplayName("nothing stored anywhere: the shipped default is off")
	void theDefaultIsOff() {
		stored(null);

		assertThat(resolver.enabled()).isFalse();
		assertThat(resolver.flagKey()).isEqualTo("project_templates");
	}

	@Test
	@DisplayName("nothing stored: the environment decides")
	void nullDefersToTheEnvironment() {
		env(true);
		stored(null);

		assertThat(resolver.enabled()).isTrue();
		assertThat(resolver.flagEnabled()).isTrue();
	}

	@Test
	@DisplayName("a stored value beats the environment, in both directions")
	void theStoredValueWins() {
		env(false);
		stored(true);
		assertThat(resolver.enabled()).isTrue();

		ProjectTemplateSettings other = new ProjectTemplateSettings(settings, properties);
		env(true);
		stored(false);
		assertThat(other.enabled()).isFalse();
	}

	@Test
	@DisplayName("a save takes effect on the next request, without a restart")
	void theSettingsEventRefreshesTheCache() {
		stored(null);
		assertThat(resolver.enabled()).isFalse();

		ServerSettings saved = new ServerSettings();
		ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
		block.setEnabled(true);
		saved.setProjectTemplates(block);
		resolver.onSettingsChanged(new SettingsService.SettingsChangedEvent(saved));

		assertThat(resolver.enabled()).isTrue();
	}

	@Test
	@DisplayName("a save that clears the switch hands it back to the environment")
	void clearingTheOverrideDefersAgain() {
		env(true);
		stored(false);
		assertThat(resolver.enabled()).isFalse();

		resolver.onSettingsChanged(new SettingsService.SettingsChangedEvent(new ServerSettings()));

		assertThat(resolver.enabled()).isTrue();
	}

	@Test
	@DisplayName("the document is read once, not once per request")
	void theBlockIsCached() {
		stored(null);

		for (int i = 0; i < 20; i++) {
			resolver.enabled();
		}

		verify(settings, times(1)).get();
	}

	@Test
	@DisplayName("a document an event carries is answered without consulting the cache")
	void enabledInAnswersForADocumentNobodyHasSeenYet() {
		env(false);
		ServerSettings incoming = new ServerSettings();
		ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
		block.setEnabled(true);
		incoming.setProjectTemplates(block);

		assertThat(resolver.enabledIn(incoming)).isTrue();
		// Listeners run in an order nobody declares, so this question must not reach for a
		// cache another listener is about to refresh.
		verify(settings, never()).get();
	}
}
