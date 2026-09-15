package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.ics.IcsCalendar;
import com.ahmadre.hinata.ics.IcsParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real feeds through the parser and into days: Google publishes holidays as dates, Apple as yearly
 * series of dates without an end, Exchange as events from midnight to midnight in a Windows time
 * zone. The Google and Apple fixtures are excerpts of the real feeds from HIN-90.
 */
class HolidayFeedTest {

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private static HolidayDays.Result daysOf(String fixture, int year) throws IOException {
		byte[] bytes;
		try (InputStream in = HolidayFeedTest.class.getResourceAsStream("/ics/" + fixture)) {
			assertThat(in).as(fixture).isNotNull();
			bytes = in.readAllBytes();
		}
		IcsCalendar calendar = IcsParser.parse(bytes, LocalDate.of(year, 1, 1).atStartOfDay(BERLIN).toInstant(),
				LocalDate.of(year + 1, 1, 1).atStartOfDay(BERLIN).toInstant(), BERLIN);
		return HolidayDays.of(calendar.events(), year);
	}

	@Test
	void exchangeHolidaysFromMidnightToMidnightAreDays() throws IOException {
		HolidayDays.Result result = daysOf("exchange-holidays.ics", 2026);

		assertThat(result.days()).containsExactly(
				new HolidayDays.Day(LocalDate.of(2026, 1, 1), "New Year's Day"),
				new HolidayDays.Day(LocalDate.of(2026, 11, 26), "Thanksgiving"),
				new HolidayDays.Day(LocalDate.of(2026, 12, 24), "Christmas Eve"));
	}

	@Test
	void googleHolidaysAreTheDatesOfTheirYear() throws IOException {
		assertThat(daysOf("google-holidays.ics", 2021).days()).containsExactly(
				new HolidayDays.Day(LocalDate.of(2021, 1, 6), "Heilige Drei Könige (regionaler Feiertag)"),
				new HolidayDays.Day(LocalDate.of(2021, 2, 14), "Valentinstag"));
		assertThat(daysOf("google-holidays.ics", 2027).days()).containsExactly(
				new HolidayDays.Day(LocalDate.of(2027, 10, 3), "Tag der Deutschen Einheit"));
	}

	@Test
	void appleSeriesWithoutAnEndGiveOneDayEachYear() throws IOException {
		assertThat(daysOf("apple-holidays.ics", 2026).days()).containsExactly(
				new HolidayDays.Day(LocalDate.of(2026, 1, 1), "Neujahr"),
				new HolidayDays.Day(LocalDate.of(2026, 1, 6), "Heilige Drei Könige"),
				new HolidayDays.Day(LocalDate.of(2026, 10, 3), "Tag der Deutschen Einheit"));
	}
}
