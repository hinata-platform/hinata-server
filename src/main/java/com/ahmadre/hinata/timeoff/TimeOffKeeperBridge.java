package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.AbsenceKeepers;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers {@link AbsenceKeepers} out of the keeper list this module already holds.
 *
 * <p>Three lines, and a class of its own rather than a second role on {@link TimeOffAccess}: the
 * bridge is what {@code availability} sees, and keeping it separate means the interface can never
 * pull the rest of {@link TimeOffAccess} along behind it. The same shape as
 * {@link TimeOffCatalogueBridge}.
 *
 * <p>It answers whether the module is switched on or off, deliberately. Somebody an operator named
 * keeps being the person who deals with absences on the day the flag is turned off to sort
 * something out — and a switch that took a right away halfway through would be a switch that
 * breaks things rather than one that hides a feature.
 */
@Component
@RequiredArgsConstructor
public class TimeOffKeeperBridge implements AbsenceKeepers {

	private final TimeOffAccess access;

	@Override
	public boolean keeps(User actor) {
		return actor != null && access.isKeeper(actor);
	}
}
