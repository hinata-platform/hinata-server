package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How the switch resolves: stored override, then environment, and transactions over both. */
class ShiftSettingsTest {

	private final HinataProperties properties = new HinataProperties();
	private final SettingsService settings = mock(SettingsService.class);
	private final ShiftSettings shifts = new ShiftSettings(settings, properties);

	private void stored(Boolean enabled) {
		ServerSettings document = new ServerSettings();
		if (enabled != null) {
			ServerSettings.Shifts block = new ServerSettings.Shifts();
			block.setEnabled(enabled);
			document.setShifts(block);
		}
		when(settings.get()).thenReturn(document);
	}

	private void environment(boolean enabled, boolean transactions) {
		properties.getShifts().setEnabled(enabled);
		properties.getMongodb().setTransactionsEnabled(transactions);
	}

	@Test
	void offOnAFreshInstance() {
		stored(null);

		assertThat(new HinataProperties().getShifts().isEnabled()).isFalse();
		assertThat(shifts.enabled()).isFalse();
		assertThat(shifts.flagKey()).isEqualTo("shift_planning");
		assertThatThrownBy(shifts::requireEnabled)
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.feature.disabled");
	}

	@Test
	void aDocumentWithoutTheBlockFollowsTheEnvironment() {
		environment(true, true);
		stored(null);

		assertThat(shifts.enabled()).isTrue();
		assertThat(shifts.flagEnabled()).isTrue();
		shifts.requireEnabled();
	}

	@Test
	void aStoredOverrideWinsOverTheEnvironment() {
		environment(true, true);
		stored(false);

		assertThat(shifts.enabled()).isFalse();
	}

	@Test
	void withoutTransactionsTheModuleStaysOffWhateverWasChosen() {
		environment(true, false);
		stored(true);

		assertThat(shifts.configured()).isTrue();
		assertThat(shifts.available()).isFalse();
		assertThat(shifts.flagAvailable()).isFalse();
		assertThat(shifts.enabled()).isFalse();
	}

	@Test
	void aSaveTakesEffectWithoutARestart() {
		environment(false, true);
		stored(null);
		assertThat(shifts.enabled()).isFalse();

		ServerSettings saved = new ServerSettings();
		ServerSettings.Shifts block = new ServerSettings.Shifts();
		block.setEnabled(true);
		saved.setShifts(block);
		shifts.onSettingsChanged(new SettingsService.SettingsChangedEvent(saved));

		assertThat(shifts.enabled()).isTrue();
	}
}
