package com.ahmadre.hinata.timeoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one number the core and this module have to agree on.
 *
 * <p>Days travel as thousandths of a working day. {@code UserWords.timeOffDays} renders them for
 * the data export and for the notice an employee gets when their entitlement changes, and it lives
 * in {@code common} because both callers need it — where {@link TimeOffType#DAY} is not importable,
 * since the core knows nothing about this module and must not start to.
 *
 * <p>So the divisor is written out there and asserted here, which is the only place that can see
 * both. If somebody ever redefines a day as hundredths, this fails rather than the export quietly
 * reporting two hundred days of leave.
 */
class TimeOffDaysTest {

	/** The divisor written into {@code UserWords.timeOffDays}. */
	private static final double RENDERED_DIVISOR = 1000.0;

	@Test
	@DisplayName("the core renders a day the way this module stores one")
	void theDivisorMirrorsTheStoredUnit() {
		assertThat(RENDERED_DIVISOR).isEqualTo(TimeOffType.DAY);
	}
}
