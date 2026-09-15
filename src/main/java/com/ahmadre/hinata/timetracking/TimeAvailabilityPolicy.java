package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.AvailabilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells availability whether leads see their members' absences: exactly when they see their
 * members' entries.
 *
 * <p>One switch for both, because they answer the same question for a works council, whether a
 * lead may see what a particular person is doing, and an instance where a lead saw the absences
 * but not the hours, or the reverse, would need two agreements for one decision. With the module
 * off there is nothing to see.
 */
@Component
@RequiredArgsConstructor
public class TimeAvailabilityPolicy implements AvailabilityPolicy {

	private final TimeTrackingSettings settings;

	@Override
	public boolean leadsSeeMemberAbsences() {
		return settings.advancedEnabled() && settings.leadsSeeMemberEntries();
	}
}
