package com.ahmadre.hinata.user;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default notification days are the working days where somebody lives. Monday
 * to Friday is the common case, and the cases that are not are exactly the ones a
 * hardcoded weekend gets wrong.
 */
class WorkWeeksTest {

	@Test
	void aBareLanguageInBerlinWorksMondayToFriday() {
		assertThat(WorkWeeks.workingDays("de", ZoneId.of("Europe/Berlin")))
				.containsExactlyElementsOf(EnumSet.range(MONDAY, FRIDAY));
	}

	@Test
	void aRegionInTheLanguageTagDecides() {
		assertThat(WorkWeeks.workingDays("ar-SA", ZoneOffset.UTC))
				.containsExactlyInAnyOrder(SUNDAY, MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, THURSDAY);
	}

	@Test
	void withoutARegionTheTimeZoneDecides() {
		assertThat(WorkWeeks.workingDays("ar", ZoneId.of("Asia/Riyadh")))
				.doesNotContain(FRIDAY, SATURDAY).contains(SUNDAY);
		assertThat(WorkWeeks.workingDays("en", ZoneId.of("Asia/Tehran")))
				.doesNotContain(FRIDAY).contains(SATURDAY, SUNDAY);
		assertThat(WorkWeeks.workingDays("en", ZoneId.of("Asia/Kolkata")))
				.doesNotContain(SUNDAY).contains(SATURDAY);
	}

	@Test
	void aLanguageAloneSaysNothingAboutAWeekend() {
		// Arabic is spoken in Morocco too, where the weekend is Saturday and Sunday.
		assertThat(WorkWeeks.workingDays("ar", ZoneId.of("Africa/Casablanca")))
				.containsExactlyElementsOf(EnumSet.range(MONDAY, FRIDAY));
	}

	@Test
	void nothingKnownIsMondayToFriday() {
		assertThat(WorkWeeks.workingDays(null, null)).containsExactlyElementsOf(EnumSet.range(MONDAY, FRIDAY));
	}
}
