package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.ahmadre.hinata.ics.IcsParseException.Reason.MALFORMED;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.NOT_A_CALENDAR;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.TOO_LARGE;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.TOO_MANY_COMPONENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The parser against what calendar providers really export, and against files built
 * to hurt it.
 *
 * <p>The two holiday calendars are cut from the live feeds of Google and iCloud
 * (14.09.2026), byte for byte. The exports with times have the shape of real Google,
 * Exchange, Outlook and macOS files: the same folding, the same time zone blocks,
 * the same vendor properties.
 */
class IcsParserTest {

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	private static final char NO_BREAK_SPACE = 0xA0;

	// --- what providers export ---------------------------------------------------------

	@Test
	void readsGoogleHolidaysAsDaysWithTheirTextUnescaped() {
		IcsCalendar calendar = parseFixture("google-holidays.ics", "2021-01-01T00:00:00Z", "2028-01-01T00:00:00Z");

		assertThat(calendar.name()).isEqualTo("Feiertage in Deutschland");
		assertThat(calendar.truncated()).isFalse();
		assertThat(calendar.events()).extracting(IcsEvent::summary).containsExactly(
				"Heilige Drei Könige (regionaler Feiertag)", "Valentinstag", "Tag der Deutschen Einheit");
		IcsEvent.AllDay epiphany = (IcsEvent.AllDay) calendar.events().get(0);
		assertThat(epiphany.start()).isEqualTo(LocalDate.of(2021, 1, 6));
		assertThat(epiphany.end()).isEqualTo(LocalDate.of(2021, 1, 7));
		assertThat(epiphany.description()).isEqualTo("Feiertag in Baden-Württemberg, Bayern, Sachsen-Anhalt");
		assertThat(epiphany.transparent()).isTrue();
		assertThat(epiphany.status()).isEqualTo(IcsEvent.Status.CONFIRMED);
		assertThat(epiphany.recurrenceId()).isNull();
		// Google folds this line in the middle of a word, and writes a no-break space before the ">".
		assertThat(calendar.events().get(1).description()).isEqualTo("Gedenktag\nWenn Sie Gedenktage ausblenden "
				+ "möchten, rufen Sie die Google Kalender-Einstellungen auf" + NO_BREAK_SPACE + "> Feiertage in Deutschland");
	}

	@Test
	void readsAppleHolidaysWithTheirYearlySeriesAndTheirDateStamp() {
		// iCloud writes DTSTAMP as a date, which RFC 5545 does not allow, and leaves DTEND out.
		IcsCalendar calendar = parseFixture("apple-holidays.ics", "2024-01-01T00:00:00Z", "2030-01-01T00:00:00Z");

		assertThat(calendar.events()).hasSize(19);
		assertThat(calendar.events()).filteredOn(event -> event.summary().equals("Neujahr"))
				.extracting(IcsEvent::recurrenceId)
				.containsExactly("20240101", "20250101", "20260101", "20270101", "20280101", "20290101");
		IcsEvent.AllDay unity = calendar.events().stream()
				.filter(event -> event.summary().equals("Tag der Deutschen Einheit"))
				.map(IcsEvent.AllDay.class::cast)
				.filter(event -> event.start().getYear() == 2026)
				.findFirst().orElseThrow();
		assertThat(unity.end()).isEqualTo(LocalDate.of(2026, 10, 4));
	}

	@Test
	void aSeriesWhoseFirstOccurrenceLiesBeforeTheWindowStillReachesIt() {
		// Every holiday series here starts in 2024. ical4j steps over the earlier years
		// without handing anything over, which an Iterator took for the end of the series.
		IcsCalendar calendar = parseFixture("apple-holidays.ics", "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z");

		assertThat(calendar.events()).extracting(IcsEvent::recurrenceId)
				.containsExactly("20260101", "20260106", "20261003");
	}

	@Test
	void readsAGoogleExportWithASeriesItsExceptionsAndTheChangeToSummerTime() {
		IcsCalendar calendar = parseFixture("google-export.ics", "2026-03-01T00:00:00Z", "2026-04-15T00:00:00Z");

		List<IcsEvent.Timed> standUps = timed(calendar, event -> event.uid().startsWith("0c7r1k8b"));
		assertThat(standUps).extracting(IcsEvent.Timed::start).containsExactly(
				Instant.parse("2026-03-02T08:30:00Z"),
				Instant.parse("2026-03-09T08:30:00Z"),
				// The 16th is struck out, and the 23rd was moved to the next day at eleven.
				Instant.parse("2026-03-24T10:00:00Z"),
				// Berlin is on summer time from the 29th: still half past nine on the wall.
				Instant.parse("2026-03-30T07:30:00Z"),
				Instant.parse("2026-04-06T07:30:00Z"),
				Instant.parse("2026-04-13T07:30:00Z"));
		IcsEvent.Timed moved = standUps.get(2);
		assertThat(moved.summary()).isEqualTo("Stand-up (verschoben)");
		assertThat(moved.recurrenceId()).isEqualTo("20260323T083000Z");
		assertThat(moved.end()).isEqualTo(Instant.parse("2026-03-24T10:15:00Z"));
		assertThat(standUps).allSatisfy(event -> assertThat(event.zone()).isEqualTo(BERLIN));

		IcsEvent.Timed planning = timed(calendar, event -> event.summary().equals("Quartalsplanung")).get(0);
		assertThat(planning.zone()).isEqualTo(ZoneOffset.UTC);
		assertThat(planning.location()).isEqualTo("Raum 3, Berlin");
		assertThat(planning.description()).isEqualTo("Agenda:\n1. Budget\n2. Einstellungen");
		assertThat(timed(calendar, event -> event.summary().equals("Review")).get(0).status())
				.isEqualTo(IcsEvent.Status.CANCELLED);
		IcsEvent.AllDay leave = calendar.events().stream().filter(event -> event.summary().equals("Urlaub"))
				.map(IcsEvent.AllDay.class::cast).findFirst().orElseThrow();
		assertThat(leave.start()).isEqualTo(LocalDate.of(2026, 4, 6));
		assertThat(leave.end()).isEqualTo(LocalDate.of(2026, 4, 11));
		assertThat(calendar.events()).hasSize(9);
	}

	@Test
	void readsAnExchangeCalendarThatNamesItsZoneTheWindowsWay() {
		IcsCalendar calendar = parseFixture("outlook-export.ics", "2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z");

		List<IcsEvent.Timed> jourFixe = timed(calendar, event -> event.summary().equals("Jour fixe"));
		assertThat(jourFixe).extracting(IcsEvent.Timed::start).containsExactly(
				Instant.parse("2026-03-03T08:00:00Z"), Instant.parse("2026-03-10T08:00:00Z"),
				Instant.parse("2026-03-17T08:00:00Z"), Instant.parse("2026-03-24T08:00:00Z"),
				Instant.parse("2026-03-31T07:00:00Z"),
				// The 7th of April is struck out, and UNTIL ends the series before the 28th.
				Instant.parse("2026-04-14T07:00:00Z"), Instant.parse("2026-04-21T07:00:00Z"));
		assertThat(jourFixe).allSatisfy(event -> assertThat(event.zone()).isEqualTo(BERLIN));
		// Exchange writes a lone line break as the description of most events.
		assertThat(jourFixe.get(0).description()).isNull();
		IcsEvent.Timed review = timed(calendar, event -> event.summary().equals("Quartalsreview")).get(0);
		assertThat(review.start()).isEqualTo(Instant.parse("2026-03-25T14:00:00Z"));
		assertThat(review.end()).isEqualTo(Instant.parse("2026-03-25T15:30:00Z"));
		assertThat(review.description()).isEqualTo("Bitte vorher die Unterlagen lesen.");
		// The company outing in June lies outside the window.
		assertThat(calendar.events()).hasSize(8);
	}

	@Test
	void readsAnOutlookExportWhoseZoneOnlyItsOwnDefinitionExplains() {
		// German Outlook calls the zone "Mitteleuropäische Zeit", which no table knows.
		IcsCalendar calendar = parseFixture("outlook-desktop-export.ics", "2026-09-01T00:00:00Z",
				"2026-10-01T00:00:00Z");

		assertThat(calendar.events()).hasSize(1);
		IcsEvent.Timed meeting = (IcsEvent.Timed) calendar.events().get(0);
		assertThat(meeting.summary()).isEqualTo("Kundentermin");
		assertThat(meeting.location()).isEqualTo("Büro");
		assertThat(meeting.start()).isEqualTo(Instant.parse("2026-09-15T08:30:00Z"));
		assertThat(meeting.end()).isEqualTo(Instant.parse("2026-09-15T09:30:00Z"));
		assertThat(meeting.floating()).isFalse();
		assertThat(meeting.zone().getRules().getOffset(meeting.start())).isEqualTo(ZoneOffset.ofHours(2));
	}

	@Test
	void appliesAZoneDefinitionInWinterAsWellAsInSummer() {
		String ics = new String(fixture("outlook-desktop-export.ics"), StandardCharsets.UTF_8)
				.replace("20260915T103000", "20261201T103000")
				.replace("20260915T113000", "20261201T113000");

		IcsEvent.Timed meeting = (IcsEvent.Timed) parse(ics, "2026-12-01T00:00:00Z", "2026-12-02T00:00:00Z")
				.events().get(0);

		assertThat(meeting.start()).isEqualTo(Instant.parse("2026-12-01T09:30:00Z"));
	}

	@Test
	void readsAMacExportAcrossTheEndOfSummerTimeWithAFloatingTimeInTheReadersZone() {
		IcsCalendar calendar = parseFixture("apple-export.ics", "2026-10-25T00:00:00Z", "2026-11-10T00:00:00Z");

		assertThat(calendar.name()).isEqualTo("Arbeit");
		assertThat(calendar.events()).extracting(IcsEvent::summary).containsExactly("Daily sync", "Daily sync",
				"Konferenz", "Daily sync", "Floating reminder", "Daily sync", "Daily sync");
		assertThat(timed(calendar, event -> event.summary().equals("Daily sync")))
				.extracting(IcsEvent.Timed::start).containsExactly(
						Instant.parse("2026-10-30T13:30:00Z"), Instant.parse("2026-10-31T13:30:00Z"),
						// New York leaves summer time on the 1st: half past nine is an hour later in UTC.
						Instant.parse("2026-11-01T14:30:00Z"), Instant.parse("2026-11-02T14:30:00Z"),
						Instant.parse("2026-11-03T14:30:00Z"));
		IcsEvent.Timed floating = timed(calendar, event -> event.summary().equals("Floating reminder")).get(0);
		// No zone in the file: three in the afternoon wherever the reader is, here Berlin in winter.
		assertThat(floating.floating()).isTrue();
		assertThat(floating.zone()).isEqualTo(BERLIN);
		assertThat(floating.start()).isEqualTo(Instant.parse("2026-11-02T14:00:00Z"));
		IcsEvent.AllDay conference = (IcsEvent.AllDay) calendar.events().get(2);
		assertThat(conference.start()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(conference.end()).isEqualTo(LocalDate.of(2026, 11, 3));
	}

	@Test
	void anUnknownZoneWithoutADefinitionIsReadLikeAFloatingTime() {
		String ics = calendar(event("nowhere",
				"DTSTART;TZID=Nowhere/Special:20260301T090000", "DTEND;TZID=Nowhere/Special:20260301T100000"));

		IcsEvent.Timed event = (IcsEvent.Timed) parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z")
				.events().get(0);

		assertThat(event.floating()).isTrue();
		assertThat(event.zone()).isEqualTo(BERLIN);
		assertThat(event.start()).isEqualTo(Instant.parse("2026-03-01T08:00:00Z"));
	}

	@Test
	void anEventWithoutAnEndLastsWhatTheStandardSays() {
		// RFC 5545 3.6.1: a date lasts the day, a date and time takes no time, DURATION says the rest.
		String ics = calendar(
				event("day", "DTSTART;VALUE=DATE:20260301"),
				event("moment", "DTSTART:20260301T090000Z"),
				event("meeting", "DTSTART:20260301T100000Z", "DURATION:PT1H30M"));

		List<IcsEvent> events = parse(ics, "2026-02-28T00:00:00Z", "2026-03-02T00:00:00Z").events();

		assertThat(((IcsEvent.AllDay) events.get(0)).end()).isEqualTo(LocalDate.of(2026, 3, 2));
		IcsEvent.Timed moment = (IcsEvent.Timed) events.get(1);
		assertThat(moment.end()).isEqualTo(moment.start());
		assertThat(((IcsEvent.Timed) events.get(2)).end()).isEqualTo(Instant.parse("2026-03-01T11:30:00Z"));
	}

	@Test
	void keepsTextShort() {
		String ics = calendar(event("long", "DTSTART:20260301T090000Z", "SUMMARY:" + "s".repeat(1000),
				"LOCATION:" + "l".repeat(1000), "DESCRIPTION:" + "d".repeat(5000)));

		IcsEvent event = parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").events().get(0);

		assertThat(event.summary()).hasSize(500);
		assertThat(event.location()).hasSize(500);
		assertThat(event.description()).hasSize(2000);
	}

	@Test
	void givesAnEventWithoutUidTheSameStandInEveryTime() {
		String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20260301T090000Z\r\nSUMMARY:A\r\n"
				+ "END:VEVENT\r\nBEGIN:VEVENT\r\nDTSTART:20260301T100000Z\r\nSUMMARY:B\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

		List<IcsEvent> first = parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").events();
		List<IcsEvent> second = parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").events();

		assertThat(first).extracting(IcsEvent::uid).doesNotContainNull().doesNotHaveDuplicates()
				.isEqualTo(second.stream().map(IcsEvent::uid).toList());
	}

	// --- windows and caps --------------------------------------------------------------

	@Test
	void expandsAnEndlessSeriesOnlyInsideTheWindow() {
		String ics = calendar(event("endless", "DTSTART:20000103T090000Z", "DTEND:20000103T100000Z", "RRULE:FREQ=WEEKLY"));

		IcsCalendar calendar = parse(ics, "2026-03-01T00:00:00Z", "2026-03-29T00:00:00Z");

		assertThat(calendar.events()).extracting(IcsEvent::recurrenceId)
				.containsExactly("20260302T090000Z", "20260309T090000Z", "20260316T090000Z", "20260323T090000Z");
		assertThat(calendar.truncated()).isFalse();
	}

	@Test
	void stopsASeriesAtFiveHundredOccurrences() {
		String ics = calendar(event("many",
				"DTSTART:20260101T090000Z", "DTEND:20260101T093000Z", "RRULE:FREQ=DAILY;COUNT=10000"));

		IcsCalendar calendar = parse(ics, "2026-01-01T00:00:00Z", "2056-01-01T00:00:00Z");

		assertThat(calendar.events()).hasSize(500);
		assertThat(calendar.truncated()).isTrue();
		assertThat(((IcsEvent.Timed) calendar.events().get(499)).start()).isEqualTo(Instant.parse("2027-05-15T09:00:00Z"));
	}

	@Test
	void stopsACalendarAtFiveThousandEvents() {
		StringBuilder events = new StringBuilder();
		for (int i = 0; i < 6000; i++) {
			events.append(event("e" + i, "DTSTART:20260301T090000Z", "DTEND:20260301T093000Z"));
		}

		IcsCalendar calendar = parse(calendar(events), "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z");

		assertThat(calendar.events()).hasSize(5000);
		assertThat(calendar.truncated()).isTrue();
	}

	@Test
	void stopsASeriesWithoutACountAtFiveHundredOccurrences() {
		// No COUNT to cut, so the only thing that can mark the result is the series cap itself.
		String ics = calendar(event("daily", "DTSTART:20260101T090000Z", "DTEND:20260101T093000Z", "RRULE:FREQ=DAILY"));

		IcsCalendar calendar = parse(ics, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z");

		assertThat(calendar.events()).hasSize(500);
		assertThat(calendar.truncated()).isTrue();
	}

	@Test
	void keepsTheEarliestFiveThousandOccurrencesOfACalendar() {
		// Eleven series of 500 a day apart by an hour each: no series is cut, the calendar is.
		CharSequence[] series = new CharSequence[11];
		for (int hour = 0; hour < series.length; hour++) {
			series[hour] = event("hour" + hour, "DTSTART:20260101T%02d0000Z".formatted(hour), "RRULE:FREQ=DAILY;COUNT=500");
		}

		IcsCalendar calendar = parse(calendar(series), "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z");

		assertThat(calendar.events()).hasSize(5000);
		assertThat(calendar.truncated()).isTrue();
		// 454 whole days of eleven, then six more: the last one kept is 05:00 on the 455th day.
		assertThat(((IcsEvent.Timed) calendar.events().get(4999)).start()).isEqualTo(Instant.parse("2027-03-31T05:00:00Z"));
	}

	@Test
	void stopsExpandingOnceTheCalendarHasSpentItsBudget() {
		// A series with COUNT is walked from its start. Fifty of 5,000 steps are what one calendar may cost.
		CharSequence[] series = new CharSequence[51];
		for (int i = 0; i < series.length; i++) {
			series[i] = event("walk" + i, "DTSTART:19900101T090000Z", "RRULE:FREQ=DAILY;COUNT=5000");
		}

		IcsCalendar calendar = assertTimeoutPreemptively(Duration.ofSeconds(30),
				() -> parse(calendar(series), "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"));

		assertThat(calendar.events()).isEmpty();
		assertThat(calendar.truncated()).isTrue();
	}

	@Test
	void refusesMoreComponentsThanAnyCalendarHas() {
		String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + "BEGIN:X-C\r\nEND:X-C\r\n".repeat(20_000) + "END:VCALENDAR\r\n";

		assertThatThrownBy(() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
				.isInstanceOfSatisfying(IcsParseException.class,
						ex -> assertThat(ex.reason()).isEqualTo(TOO_MANY_COMPONENTS));
	}

	@Test
	void aDailySeriesFromTheYearOneReachesTheWindowAtOnce() {
		String ics = calendar(event("ancient", "DTSTART:00010101T090000Z", "DTEND:00010101T100000Z", "RRULE:FREQ=DAILY"));

		IcsCalendar calendar = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"));

		assertThat(calendar.events()).extracting(IcsEvent::recurrenceId).containsExactly("20260301T090000Z");
	}

	@Test
	void aSeriesMoreFrequentThanDailyIsNotExpandedAndSaysSo() {
		// ical4j's jump to the window overflows an int for these, and then it never returns.
		String ics = calendar(
				event("minutely", "DTSTART:19000101T000000Z", "DTEND:19000101T000100Z", "RRULE:FREQ=MINUTELY"),
				event("secondly", "DTSTART:20260301T120000Z", "RRULE:FREQ=SECONDLY"));

		IcsCalendar calendar = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"));

		assertThat(calendar.events()).extracting(IcsEvent::uid).containsExactly("secondly");
		assertThat(calendar.truncated()).isTrue();
	}

	@Test
	void aHugeCountIsCutRatherThanWalked() {
		String ics = calendar(event("forever", "DTSTART:19000101T090000Z", "RRULE:FREQ=DAILY;COUNT=2000000000"));

		IcsCalendar calendar = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"));

		// Cut at 5,000 days the series ends in 1913, and the result says there was more.
		assertThat(calendar.events()).isEmpty();
		assertThat(calendar.truncated()).isTrue();
	}

	@Test
	void aRuleThatMultipliesIntoMillionsOfCandidatesIsNotExpanded() {
		String ics = calendar(event("dense", "DTSTART:20260301T000000Z", "RRULE:FREQ=YEARLY;BYMONTH=" + range(1, 12)
				+ ";BYMONTHDAY=" + range(1, 31) + ";BYHOUR=" + range(0, 23)));

		IcsCalendar calendar = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"));

		assertThat(calendar.events()).extracting(IcsEvent::recurrenceId).containsExactly("20260301T000000Z");
		assertThat(calendar.truncated()).isTrue();
	}

	// --- files that are broken or built to hurt ----------------------------------------

	@Test
	void refusesWhatIsNotACalendar() {
		for (String notACalendar : List.of("", "<!DOCTYPE html><html><body>Sign in</body></html>",
				"BEGIN:VCARD\r\nVERSION:4.0\r\nEND:VCARD\r\n")) {
			assertThatThrownBy(() -> parse(notACalendar, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
					.isInstanceOfSatisfying(IcsParseException.class,
							ex -> assertThat(ex.reason()).isEqualTo(NOT_A_CALENDAR));
		}
	}

	@Test
	void refusesMoreThanTwoMegabytesBeforeReadingAnyOfIt() {
		byte[] large = new byte[2 * 1024 * 1024 + 1];
		Arrays.fill(large, (byte) 'A');

		assertThatThrownBy(() -> IcsParser.parse(large, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), BERLIN))
				.isInstanceOfSatisfying(IcsParseException.class, ex -> assertThat(ex.reason()).isEqualTo(TOO_LARGE));
	}

	@Test
	void refusesNestingDeeperThanACalendarNeedsAtTheLineItHappens() {
		StringBuilder deep = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
		deep.append("BEGIN:X-NEST\r\n".repeat(100_000));

		assertThatThrownBy(() -> parse(deep.toString(), "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
				.isInstanceOfSatisfying(IcsParseException.class, ex -> {
					assertThat(ex.reason()).isEqualTo(MALFORMED);
					assertThat(ex.line()).isBetween(3, 20);
				});
	}

	@Test
	void refusesALineLongerThanAnyCalendarWrites() {
		String ics = calendar(event("long", "DTSTART:20260301T090000Z", "SUMMARY:" + "x".repeat(300_000)));

		assertThatThrownBy(() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
				.isInstanceOfSatisfying(IcsParseException.class, ex -> assertThat(ex.reason()).isEqualTo(MALFORMED));
	}

	@Test
	void refusesABrokenValueAtItsLineAndNeverWithTheLibrarysOwnException() {
		List<String> broken = List.of(
				"DTSTART:2026-99-99T25:61:00",
				"DTSTART;TZID=Europe/Berlin:20261399T000000",
				"DTSTART:20260301T090000Z\r\nRRULE:FREQ=SOMETIMES",
				"DTSTART:20260301T090000Z\r\nDURATION:PT1X",
				"DTSTART:20260301T090000Z\r\nEXDATE:yesterday");
		for (String properties : broken) {
			String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:broken\r\n" + properties
					+ "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
			int line = 4 + properties.split("\r\n").length;

			assertThatThrownBy(() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
					.as(properties)
					.isInstanceOfSatisfying(IcsParseException.class, ex -> {
						assertThat(ex.reason()).isEqualTo(MALFORMED);
						assertThat(ex.line()).isEqualTo(line);
						assertThat(ex).hasNoCause();
					});
		}
	}

	@Test
	void refusesAStructureThatDoesNotClose() {
		for (String ics : List.of("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n",
				"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20260301T090000Z\r\nEND:VCALENDAR\r\n")) {
			assertThatThrownBy(() -> parse(ics, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z"))
					.isInstanceOfSatisfying(IcsParseException.class, ex -> assertThat(ex.reason()).isEqualTo(MALFORMED));
		}
	}

	@Test
	void nothingButACalendarOrAParseExceptionEverComesOut() {
		byte[] export = fixture("google-export.ics");
		Random random = new Random(90);
		for (int cut = 0; cut < export.length; cut += 7) {
			readOrRefuse(Arrays.copyOf(export, cut));
			byte[] damaged = export.clone();
			for (int i = 0; i < 12; i++) {
				damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
			}
			readOrRefuse(damaged);
		}
	}

	@Test
	void aRefusalNamesTheLineTheWayAPersonWritesIt() {
		ResourceBundle german = ResourceBundle.getBundle("messages", Locale.GERMAN);

		String sentence = new MessageFormat(german.getString(new IcsParseException(MALFORMED, 12_345).messageKey()),
				Locale.GERMAN).format(new Object[] { 12_345 });

		assertThat(sentence).isEqualTo("Die Kalenderdatei ist fehlerhaft (Zeile 12345).");
		// Without a line there is no "line 0" to show.
		assertThat(new IcsParseException(MALFORMED, 0).messageKey()).isEqualTo("error.ics.unreadable");
	}

	@Test
	void refusesAWindowThatIsNone() {
		byte[] ics = fixture("google-export.ics");
		Instant now = Instant.parse("2026-03-01T00:00:00Z");

		assertThatThrownBy(() -> IcsParser.parse(ics, now, now, BERLIN)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IcsParser.parse(ics, null, now, BERLIN)).isInstanceOf(IllegalArgumentException.class);
	}

	// --- helpers ------------------------------------------------------------------------

	/** Anything other than a result or an IcsParseException fails the test on its own. */
	private static void readOrRefuse(byte[] ics) {
		try {
			IcsParser.parse(ics, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-15T00:00:00Z"), BERLIN);
		}
		catch (IcsParseException expected) {
			// refused, which is one of the two allowed outcomes
		}
	}

	private static IcsCalendar parseFixture(String name, String from, String to) {
		return IcsParser.parse(fixture(name), Instant.parse(from), Instant.parse(to), BERLIN);
	}

	private static IcsCalendar parse(String ics, String from, String to) {
		return IcsParser.parse(ics.getBytes(StandardCharsets.UTF_8), Instant.parse(from), Instant.parse(to), BERLIN);
	}

	private static byte[] fixture(String name) {
		try (InputStream in = IcsParserTest.class.getResourceAsStream("/ics/" + name)) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String calendar(CharSequence... events) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//hinata//tests//EN\r\n" + String.join("", events)
				+ "END:VCALENDAR\r\n";
	}

	private static String event(String uid, String... properties) {
		return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260914T120000Z\r\n" + String.join("\r\n", properties)
				+ "\r\nEND:VEVENT\r\n";
	}

	private static String range(int from, int to) {
		return IntStream.rangeClosed(from, to).mapToObj(String::valueOf).collect(Collectors.joining(","));
	}

	private static List<IcsEvent.Timed> timed(IcsCalendar calendar, Predicate<IcsEvent> filter) {
		return calendar.events().stream().filter(filter).map(IcsEvent.Timed.class::cast).toList();
	}
}
