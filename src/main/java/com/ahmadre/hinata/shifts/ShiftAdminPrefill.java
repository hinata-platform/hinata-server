package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsPrefill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells the admin area what the shift-planning switch currently resolves to, without touching
 * what is stored.
 *
 * <p>{@code settings.shifts.enabled} is what an administrator decided, null on a fresh instance,
 * meaning "whatever the environment says". {@code settings.shifts.effective.enabled} is what that
 * null works out to. The admin area shows the second and edits the first, so the switch can be
 * handed back to the environment. Whether the deployment can run the module at all is
 * {@code shiftPlanningAvailable} in {@code /meta}, not this view: the effective value here is the
 * switch's position, which stays what it was left at even while transactions are off.
 */
@Component
@RequiredArgsConstructor
public class ShiftAdminPrefill implements SettingsPrefill {

	private final ShiftSettings settings;

	@Override
	public void prefill(ServerSettings document) {
		ServerSettings.Shifts stored = document.getShifts();
		if (stored == null) {
			// So the admin area always has a block to write an override into. Empty, not filled:
			// the field stays null and keeps deferring to the environment.
			stored = new ServerSettings.Shifts();
			document.setShifts(stored);
		}
		ServerSettings.Shifts view = new ServerSettings.Shifts();
		view.setEnabled(settings.configured());
		stored.setEffective(view);
	}
}
