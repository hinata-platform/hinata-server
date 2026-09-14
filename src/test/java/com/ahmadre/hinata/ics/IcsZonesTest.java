package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class IcsZonesTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			// As Google and Apple write them.
			"Europe/Berlin|Europe/Berlin",
			"America/New_York|America/New_York",
			// As Exchange and Outlook write them (seen in published calendars 2026-09-14).
			"W. Europe Standard Time|Europe/Berlin",
			"Eastern Standard Time|America/New_York",
			"Central Standard Time|America/Chicago",
			// Older Thunderbird and Evolution exports, and Outlook's own UTC.
			"/mozilla.org/20050126_1/Europe/Berlin|Europe/Berlin",
			"/softwarestudio.org/Olson_20011030_5/America/Argentina/Buenos_Aires|America/Argentina/Buenos_Aires",
			"tzone://Microsoft/Utc|UTC",
			// Quoted, padded, or in the wrong case.
			"\"Europe/Berlin\"|Europe/Berlin",
			"  europe/berlin |Europe/Berlin" })
	void findsTheZoneBehindTheWaysCalendarsNameIt(String tzid, String zone) {
		assertThat(IcsZones.byName(tzid)).contains(ZoneId.of(zone));
	}

	@Test
	void theUtcOfWindowsAndOfIanaAreTheSameClock() {
		Instant now = Instant.parse("2026-09-14T12:00:00Z");
		assertThat(IcsZones.byName("UTC")).hasValueSatisfying(
				zone -> assertThat(zone.getRules().getOffset(now).getTotalSeconds()).isZero());
	}

	@ParameterizedTest
	@ValueSource(strings = { "Mitteleuropäische Zeit", "Customized Time Zone", "tzone://Microsoft/Custom", "", " ",
			"Europe/Atlantis" })
	void leavesANameThatOnlyTheFileExplainsUnresolved(String tzid) {
		assertThat(IcsZones.byName(tzid)).isEmpty();
	}

	@Test
	void doesNotEvenLookAtAnAbsurdlyLongName() {
		assertThat(IcsZones.byName("x/".repeat(100) + "Europe/Berlin")).isEmpty();
		assertThat(IcsZones.byName(null)).isEmpty();
	}
}
