package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.ics.IcsEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which events of a feed become holidays, with fixed dates. */
class HolidayDaysTest {

	private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

	private static IcsEvent.AllDay allDay(String name, LocalDate start, LocalDate end) {
		return new IcsEvent.AllDay("u-" + name + start, null, start, end, name, null, null, null, true);
	}

	private static IcsEvent.Timed timed(String name, ZoneId zone, String start, String end) {
		return new IcsEvent.Timed("u-" + name, null,
				java.time.LocalDateTime.parse(start).atZone(zone).toInstant(),
				java.time.LocalDateTime.parse(end).atZone(zone).toInstant(),
				zone, false, name, null, null, null, false);
	}

	@Test
	void anAllDayEventOverTwoDaysIsTwoHolidays() {
		HolidayDays.Result result = HolidayDays.of(List.of(
				allDay("Weihnachten", LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 27))), 2026);

		assertThat(result.days()).extracting(HolidayDays.Day::date)
				.containsExactly(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26));
		assertThat(result.capped()).isZero();
	}

	@Test
	void anEventFromMidnightToMidnightInItsOwnZoneIsADay() {
		// Exchange writes holidays this way: TZID=Central Standard Time, 00:00 to 00:00 next day.
		HolidayDays.Result result = HolidayDays.of(List.of(
				timed("Thanksgiving", CHICAGO, "2026-11-26T00:00", "2026-11-27T00:00")), 2026);

		assertThat(result.days()).containsExactly(new HolidayDays.Day(LocalDate.of(2026, 11, 26), "Thanksgiving"));
	}

	@Test
	void aStartEqualToItsEndAtMidnightIsOneDay() {
		// Christmas Eve at Northwestern.
		HolidayDays.Result result = HolidayDays.of(List.of(
				timed("Christmas Eve", CHICAGO, "2026-12-24T00:00", "2026-12-24T00:00")), 2026);

		assertThat(result.days()).extracting(HolidayDays.Day::date).containsExactly(LocalDate.of(2026, 12, 24));
	}

	@Test
	void anEventAtATimeOfDayIsAnAppointment() {
		HolidayDays.Result result = HolidayDays.of(List.of(
				timed("Team lunch", ZoneOffset.UTC, "2026-12-23T12:00", "2026-12-23T13:00"),
				timed("Late holiday", CHICAGO, "2026-12-30T00:00", "2026-12-30T18:00")), 2026);

		assertThat(result.days()).isEmpty();
	}

	@Test
	void cancelledAndNamelessEventsAreSkipped() {
		IcsEvent cancelled = new IcsEvent.AllDay("u1", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2),
				"Tag der Arbeit", null, null, IcsEvent.Status.CANCELLED, true);
		IcsEvent nameless = allDay(" ", LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 3));

		assertThat(HolidayDays.of(List.of(cancelled, nameless), 2026).days()).isEmpty();
	}

	@Test
	void onlyTheDaysOfTheYearCount_andTwoEventsOnADayMakeOneHoliday() {
		HolidayDays.Result result = HolidayDays.of(List.of(
				allDay("Silvester bis Neujahr", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 2)),
				allDay("Neujahr", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2)),
				allDay("Zweimal", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1))), 2026);

		assertThat(result.days()).containsExactly(
				new HolidayDays.Day(LocalDate.of(2026, 12, 31), "Silvester bis Neujahr"));
	}

	@Test
	void atMostAHundredHolidaysAYear_theEarliest() {
		List<IcsEvent> events = new ArrayList<>();
		for (int day = 0; day < 120; day++) {
			LocalDate date = LocalDate.of(2026, 1, 1).plusDays(day);
			events.add(allDay("Tag " + day, date, date.plusDays(1)));
		}

		HolidayDays.Result result = HolidayDays.of(events, 2026);

		assertThat(result.days()).hasSize(100);
		assertThat(result.days().getLast().date()).isEqualTo(LocalDate.of(2026, 4, 10));
		assertThat(result.capped()).isEqualTo(20);
	}

	@Test
	@Timeout(5)
	void eventsSpanningMillenniaAreWalkedOnlyThroughTheYear() {
		// A feed of two megabytes can hold thousands of events from the year 1 on. Walked day by day
		// from their start that was billions of steps after the parser's budget was spent.
		List<IcsEvent> events = new ArrayList<>();
		for (int index = 0; index < 2_000; index++) {
			events.add(allDay("Forever " + index, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)));
		}

		HolidayDays.Result result = HolidayDays.of(events, 2026);

		assertThat(result.days()).hasSize(100);
		assertThat(result.days().getFirst()).isEqualTo(new HolidayDays.Day(LocalDate.of(2026, 1, 1), "Forever 0"));
		assertThat(result.capped()).isEqualTo(265);
	}
}
