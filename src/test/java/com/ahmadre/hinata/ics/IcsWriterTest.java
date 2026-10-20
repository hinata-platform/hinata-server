package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer against a golden file, the escaping against injection, folding against multi-byte
 * text, and the times against the night the clocks go back (25 October 2026 in Europe). Where it
 * matters the output is read back with {@link IcsParser}, the reader the rest of the platform trusts.
 */
class IcsWriterTest {

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final Instant STAMP = Instant.parse("2026-10-20T08:00:00Z");
	private static final String INJECTION = "Kasse\rURL:https://evil.example\rBEGIN:VALARM\r\nTRIGGER:-PT1M\nEND:VALARM";

	@Test
	@DisplayName("a feed of four events is written byte for byte as the golden file")
	void matchesTheGoldenFile() throws Exception {
		byte[] written = IcsWriter.bytes(golden());
		byte[] expected;
		try (InputStream in = getClass().getResourceAsStream("/ics/shift-feed-golden.ics")) {
			assertThat(in).as("golden file").isNotNull();
			expected = in.readAllBytes();
		}
		if (!Arrays.equals(written, expected)) {
			Path actual = Path.of("build", "shift-feed-golden.actual.ics");
			Files.createDirectories(actual.getParent());
			Files.write(actual, written);
		}
		assertThat(new String(written, StandardCharsets.UTF_8)).isEqualTo(new String(expected, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("a title cannot start a property or a component of its own")
	void injectionStaysInsideItsProperty() {
		String ics = IcsWriter.write(calendar(event("u1", INJECTION, "Halle 3;\nBEGIN:VEVENT", BERLIN)));

		List<String> logical = Arrays.asList(unfold(ics).split("\r\n"));
		assertThat(logical).filteredOn(line -> line.startsWith("BEGIN:")).containsExactly(
				"BEGIN:VCALENDAR", "BEGIN:VTIMEZONE", "BEGIN:DAYLIGHT", "BEGIN:VEVENT");
		assertThat(logical).noneMatch(line -> line.startsWith("URL:https://evil") || line.startsWith("TRIGGER"));
		assertThat(logical).contains(
				"SUMMARY:Kasse\\nURL:https://evil.example\\nBEGIN:VALARM\\nTRIGGER:-PT1M\\nEND:VALARM",
				"LOCATION:Halle 3\\;\\nBEGIN:VEVENT");

		IcsCalendar read = IcsParser.parse(ics.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-10-01T00:00:00Z"),
				Instant.parse("2026-11-30T00:00:00Z"), BERLIN);
		assertThat(read.events()).hasSize(1);
		assertThat(read.events().get(0).summary())
				.isEqualTo("Kasse\nURL:https://evil.example\nBEGIN:VALARM\nTRIGGER:-PT1M\nEND:VALARM");
	}

	@Test
	@DisplayName("backslash first, then semicolon and comma, then line breaks; other control characters go")
	void escapesInOrder() {
		assertThat(IcsWriter.escape("a\\;b")).isEqualTo("a\\\\\\;b");
		assertThat(IcsWriter.escape("a\\nb")).as("a literal backslash-n stays two characters").isEqualTo("a\\\\nb");
		assertThat(IcsWriter.escape("Kasse, Sommerfest")).isEqualTo("Kasse\\, Sommerfest");
		assertThat(IcsWriter.escape("a\r\nb\rc\nd")).isEqualTo("a\\nb\\nc\\nd");
		assertThat(IcsWriter.escape("a\u0000b\u0007c\td\u001Be\u007Ff")).isEqualTo("abcdef");
		assertThat(IcsWriter.escape(null)).isEmpty();
	}

	@Test
	@DisplayName("lines fold at 75 octets and never inside a UTF-8 sequence")
	void foldsOnCodePointBoundaries() {
		String summary = "Öffnungsdienst " + "ä".repeat(40) + " 🐝🐝🐝 " + "日本語".repeat(20);
		String ics = IcsWriter.write(calendar(event("u1", summary, null, BERLIN)));

		for (String line : ics.split("\r\n")) {
			assertThat(line.getBytes(StandardCharsets.UTF_8).length).as(line).isLessThanOrEqualTo(75);
			// Each physical line is valid UTF-8 on its own: no sequence was cut.
			assertThat(new String(line.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(line);
			assertThat(line).doesNotContain("�");
		}
		assertThat(unfold(ics)).contains("SUMMARY:" + summary);
		IcsCalendar read = IcsParser.parse(ics.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-10-01T00:00:00Z"),
				Instant.parse("2026-11-30T00:00:00Z"), BERLIN);
		assertThat(read.events().get(0).summary()).isEqualTo(summary);
	}

	@Test
	@DisplayName("a shift through the night the clocks go back keeps its real length")
	void timesAcrossTheChangeToWinterTime() {
		// 25 October 2026: 03:00 CEST becomes 02:00 CET. 22:00 to 06:00 is eight hours on the wall
		// and nine in fact.
		Instant start = Instant.parse("2026-10-24T20:00:00Z");
		Instant end = Instant.parse("2026-10-25T05:00:00Z");
		String ics = IcsWriter.write(calendar(new IcsWriter.Event("night@h", start, end, BERLIN, "Nachtwache", null,
				null, null, IcsWriter.Status.CONFIRMED)));

		assertThat(ics).contains("DTSTART;TZID=Europe/Berlin:20261024T220000\r\n",
				"DTEND;TZID=Europe/Berlin:20261025T060000\r\n",
				"BEGIN:STANDARD\r\nDTSTART:20261025T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\n");
		IcsEvent.Timed read = (IcsEvent.Timed) IcsParser.parse(ics.getBytes(StandardCharsets.UTF_8),
				Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z"), ZoneOffset.UTC)
				.events().get(0);
		assertThat(read.start()).isEqualTo(start);
		assertThat(read.end()).isEqualTo(end);
		assertThat(Duration.between(read.start(), read.end())).isEqualTo(Duration.ofHours(9));
	}

	@Test
	@DisplayName("the second pass through the repeated hour is written in UTC, the first in local time")
	void theRepeatedHourIsNeverAmbiguous() {
		assertThat(IcsWriter.time(Instant.parse("2026-10-25T00:30:00Z"), BERLIN))
				.isEqualTo(";TZID=Europe/Berlin:20261025T023000");
		assertThat(IcsWriter.time(Instant.parse("2026-10-25T01:30:00Z"), BERLIN)).isEqualTo(":20261025T013000Z");
		assertThat(IcsWriter.time(Instant.parse("2026-10-25T01:30:00Z"), ZoneOffset.UTC)).isEqualTo(":20261025T013000Z");
		assertThat(IcsWriter.time(Instant.parse("2026-10-25T01:30:00Z"), ZoneId.of("UTC"))).isEqualTo(":20261025T013000Z");
	}

	@Test
	void offsetsCarrySecondsOnlyWhenThereAreAny() {
		assertThat(IcsWriter.offset(ZoneOffset.ofHours(1))).isEqualTo("+0100");
		assertThat(IcsWriter.offset(ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("-0330");
		assertThat(IcsWriter.offset(ZoneOffset.ofTotalSeconds(3600 + 23 * 60 + 15))).isEqualTo("+012315");
	}

	@Test
	@DisplayName("the same events give the same UIDs on every write")
	void writingTwiceGivesTheSameEvents() {
		IcsWriter.Calendar calendar = golden();
		assertThat(IcsWriter.write(calendar)).isEqualTo(IcsWriter.write(calendar));
		IcsCalendar read = IcsParser.parse(IcsWriter.bytes(calendar), Instant.parse("2026-10-01T00:00:00Z"),
				Instant.parse("2026-12-31T00:00:00Z"), BERLIN);
		assertThat(read.events()).extracting(IcsEvent::uid).doesNotHaveDuplicates().hasSize(4);
		assertThat(read.events()).extracting(IcsEvent::status).containsExactlyInAnyOrder(IcsEvent.Status.CONFIRMED,
				IcsEvent.Status.TENTATIVE, IcsEvent.Status.CANCELLED, IcsEvent.Status.CONFIRMED);
	}

	private static IcsWriter.Calendar golden() {
		return new IcsWriter.Calendar("-//Hinata//Shifts//EN", "Hinata Schichten", STAMP, Duration.ofHours(1), List.of(
				new IcsWriter.Event("a1@hinata.example.org", Instant.parse("2026-10-24T20:00:00Z"),
						Instant.parse("2026-10-25T05:00:00Z"), BERLIN, "Kasse, Sommerfest", "Halle 3; Eingang Nord",
						"In Hinata öffnen: https://app.example.org/shifts/events/e1",
						"https://app.example.org/shifts/events/e1", IcsWriter.Status.CONFIRMED),
				new IcsWriter.Event("a2@hinata.example.org", Instant.parse("2026-10-25T01:30:00Z"),
						Instant.parse("2026-10-25T03:00:00Z"), BERLIN, INJECTION, null, null, null,
						IcsWriter.Status.TENTATIVE),
				new IcsWriter.Event("a3@hinata.example.org", Instant.parse("2026-10-28T09:00:00Z"),
						Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC,
						"Werkstattdienst, Tag der offenen Tür im Bürgeramt mit Führungen für alle Gäste 🐝", "Rathaus",
						null, null, IcsWriter.Status.CANCELLED),
				new IcsWriter.Event("a4@hinata.example.org", Instant.parse("2026-10-31T14:00:00Z"),
						Instant.parse("2026-11-02T15:00:00Z"), NEW_YORK, "Messestand, Trade Show", "Javits Center",
						null, null, IcsWriter.Status.CONFIRMED)));
	}

	private static IcsWriter.Calendar calendar(IcsWriter.Event event) {
		return new IcsWriter.Calendar("-//Hinata//Shifts//EN", "Hinata", STAMP, null, List.of(event));
	}

	private static IcsWriter.Event event(String uid, String summary, String location, ZoneId zone) {
		return new IcsWriter.Event(uid, Instant.parse("2026-10-21T08:00:00Z"), Instant.parse("2026-10-21T12:00:00Z"),
				zone, summary, location, null, null, IcsWriter.Status.CONFIRMED);
	}

	private static String unfold(String ics) {
		return ics.replace("\r\n ", "");
	}
}
