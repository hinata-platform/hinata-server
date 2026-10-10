package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Refuses a platform holiday region nobody has rules for. The pattern on the field only knows what a
 * code looks like; without this, "XX" would be stored, answered with 200 and quietly mean "none".
 */
@Component
@RequiredArgsConstructor
public class HolidaySettingsGuard implements SettingsGuard {

	private final HolidayRules rules;

	@Override
	public void check(ServerSettings before, ServerSettings after) {
		String region = after.getHolidays() == null ? null : after.getHolidays().getRegion();
		if (region == null || region.isBlank() || ServerSettings.Holidays.NONE.equals(region)) {
			return;
		}
		if (rules.normalize(region).isEmpty()) {
			throw ApiException.badRequest("error.availability.rulesUnknown");
		}
	}
}
