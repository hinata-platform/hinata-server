package com.ahmadre.hinata.ics;

import com.ahmadre.hinata.ics.IcsZoneDefinitions.Observance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zone rules a calendar defines itself, measured against the real zones they stand
 * for: every hour of two years must carry the same offset.
 */
class IcsZoneDefinitionsTest {

	/** As German Outlook writes it, under the name "Mitteleuropäische Zeit". */
	private static final List<Observance> CENTRAL_EUROPE = List.of(
			new Observance(false, "16011028T030000", "+0200", "+0100", "FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10"),
			new Observance(true, "16010325T020000", "+0100", "+0200", "FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3"));

	/** As Exchange publishes "Eastern Standard Time" (seen 2026-09-14). */
	private static final List<Observance> EXCHANGE_EASTERN = List.of(
			new Observance(false, "16010101T020000", "-0400", "-0500", "FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11"),
			new Observance(true, "16010101T020000", "-0500", "-0400", "FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3"));

	@Test
	void aLastSundayRuleKeepsBerlinsClock() {
		assertSameOffsets(IcsZoneDefinitions.rules(CENTRAL_EUROPE).orElseThrow(), "Europe/Berlin");
	}

	@Test
	void aSecondSundayRuleKeepsNewYorksClock() {
		assertSameOffsets(IcsZoneDefinitions.rules(EXCHANGE_EASTERN).orElseThrow(), "America/New_York");
	}

	@Test
	void summerAcrossNewYearKeepsSydneysClock() {
		ZoneRules rules = IcsZoneDefinitions.rules(List.of(
				new Observance(false, "20080406T030000", "+1100", "+1000", "FREQ=YEARLY;BYMONTH=4;BYDAY=1SU"),
				new Observance(true, "20081005T020000", "+1000", "+1100", "FREQ=YEARLY;BYMONTH=10;BYDAY=1SU")))
				.orElseThrow();

		assertSameOffsets(rules, "Australia/Sydney");
	}

	@Test
	void resolvesTheGapAndTheOverlapTheWayRfc5545Says() {
		ZoneRules rules = IcsZoneDefinitions.rules(CENTRAL_EUROPE).orElseThrow();

		// 02:30 on the morning the clocks go forward does not exist: read with the offset before the gap.
		assertThat(IcsZones.instant(IcsValues.dateTime("20260329T023000"), rules))
				.isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
		// 02:30 on the morning they go back happens twice: the first one counts.
		assertThat(IcsZones.instant(IcsValues.dateTime("20261025T023000"), rules))
				.isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
	}

	@Test
	void usesTheNewestBlockOfEachKind() {
		// Europe changed back in October from 1996; before that it was September.
		ZoneRules rules = IcsZoneDefinitions.rules(List.of(
				new Observance(true, "19810329T020000", "+0100", "+0200", "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"),
				new Observance(false, "19810927T030000", "+0200", "+0100", "FREQ=YEARLY;BYMONTH=9;BYDAY=-1SU"),
				new Observance(false, "19961027T030000", "+0200", "+0100", "FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU")))
				.orElseThrow();

		assertThat(rules.getOffset(Instant.parse("2026-10-10T12:00:00Z"))).isEqualTo(ZoneOffset.ofHours(2));
	}

	@Test
	void aZoneWithoutSummerTimeIsItsOffset() {
		// Exchange writes UTC as two blocks with the same offset.
		ZoneRules utc = IcsZoneDefinitions.rules(List.of(
				new Observance(false, "16010101T000000", "+0000", "+0000", null),
				new Observance(true, "16010101T000000", "+0000", "+0000", null))).orElseThrow();
		ZoneRules india = IcsZoneDefinitions.rules(List.of(
				new Observance(false, "19700101T000000", "+0530", "+0530", null))).orElseThrow();

		assertThat(utc.isFixedOffset()).isTrue();
		assertThat(utc.getOffset(Instant.parse("2026-07-01T00:00:00Z"))).isEqualTo(ZoneOffset.UTC);
		assertThat(india.getOffset(Instant.parse("2026-07-01T00:00:00Z"))).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
	}

	@Test
	void fallsBackToTheStandardOffsetForARuleItDoesNotRead() {
		ZoneRules rules = IcsZoneDefinitions.rules(List.of(
				new Observance(false, "20001029T030000", "+0200", "+0100", "FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"),
				new Observance(true, "20000325T020000", "+0100", "+0200", "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=25")))
				.orElseThrow();

		assertThat(rules.isFixedOffset()).isTrue();
		assertThat(rules.getOffset(Instant.parse("2026-07-01T00:00:00Z"))).isEqualTo(ZoneOffset.ofHours(1));
	}

	@Test
	void describesNothingWhenNoBlockCanBeRead() {
		assertThat(IcsZoneDefinitions.rules(List.of(
				new Observance(false, "someday", "+0100", "+0100", null),
				new Observance(true, "20000101T000000", "one hour", "+0200", null)))).isEmpty();
		assertThat(IcsZoneDefinitions.rules(List.of())).isEmpty();
	}

	private static void assertSameOffsets(ZoneRules rules, String zone) {
		ZoneRules real = ZoneId.of(zone).getRules();
		Instant end = Instant.parse("2028-01-01T00:00:00Z");
		for (Instant at = Instant.parse("2026-01-01T00:00:00Z"); at.isBefore(end); at = at.plus(Duration.ofHours(1))) {
			assertThat(rules.getOffset(at)).as("%s at %s", zone, at).isEqualTo(real.getOffset(at));
		}
	}
}
