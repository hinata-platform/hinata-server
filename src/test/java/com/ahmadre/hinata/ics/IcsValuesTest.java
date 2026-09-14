package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsValuesTest {

	@Test
	void readsDatesAndDateTimes() {
		assertThat(IcsValues.date("20260301")).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(IcsValues.dateTime("20260301T093000")).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 30));
		assertThat(IcsValues.dateTime("20260301T093000Z")).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 30));
		assertThat(IcsValues.isUtc("20260301T093000Z")).isTrue();
		assertThat(IcsValues.isUtc("20260301T093000")).isFalse();
		// A leap second, which java.time cannot hold.
		assertThat(IcsValues.dateTime("20161231T235960Z")).isEqualTo(LocalDateTime.of(2016, 12, 31, 23, 59, 59));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-03-01", "20261399", "20260230", "2026030", "yesterday", "" })
	void refusesWhatIsNotADate(String value) {
		assertThatThrownBy(() -> IcsValues.date(value)).isInstanceOf(DateTimeException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-99-99T25:61:00", "20261399T000000", "20260301T250000", "20260301 093000",
			"20260301T0930", "20260301T093000+0100" })
	void refusesWhatIsNotADateTime(String value) {
		assertThatThrownBy(() -> IcsValues.dateTime(value)).isInstanceOf(DateTimeException.class);
	}

	@Test
	void readsDurationsAsDaysAndSeconds() {
		assertThat(IcsValues.duration("PT1H30M")).isEqualTo(new IcsValues.Span(0, 5400));
		assertThat(IcsValues.duration("P2D")).isEqualTo(new IcsValues.Span(2, 0));
		assertThat(IcsValues.duration("P1W")).isEqualTo(new IcsValues.Span(7, 0));
		assertThat(IcsValues.duration("P1DT12H")).isEqualTo(new IcsValues.Span(1, 43200));
		assertThat(IcsValues.duration("+PT45S")).isEqualTo(new IcsValues.Span(0, 45));
		assertThat(IcsValues.duration("-PT15M")).isEqualTo(new IcsValues.Span(0, -900));
	}

	@ParameterizedTest
	@ValueSource(strings = { "P", "PT", "PT1X", "P1W2D", "1H", "PT1H30", "P12345678D" })
	void refusesWhatIsNotADuration(String value) {
		assertThatThrownBy(() -> IcsValues.duration(value)).isInstanceOf(DateTimeException.class);
	}

	@Test
	void readsUtcOffsets() {
		assertThat(IcsValues.offset("+0100")).isEqualTo(ZoneOffset.ofHours(1));
		assertThat(IcsValues.offset("-0500")).isEqualTo(ZoneOffset.ofHours(-5));
		assertThat(IcsValues.offset("+053045")).isEqualTo(ZoneOffset.ofHoursMinutesSeconds(5, 30, 45));
		assertThatThrownBy(() -> IcsValues.offset("0100")).isInstanceOf(DateTimeException.class);
		assertThatThrownBy(() -> IcsValues.offset("+2500")).isInstanceOf(DateTimeException.class);
	}

	@Test
	void undoesTheEscapesOfText() {
		assertThat(IcsValues.text("Raum 3\\, Berlin\\; zweiter Stock\\nFlur links\\N\\\\Tür", 500))
				.isEqualTo("Raum 3, Berlin; zweiter Stock\nFlur links\n\\Tür");
	}

	@Test
	void keepsTextShortAndCountsALoneLineBreakAsNothing() {
		assertThat(IcsValues.text("x".repeat(3000), 2000)).hasSize(2000);
		assertThat(IcsValues.text("\\n", 500)).isNull();
		assertThat(IcsValues.text("  ", 500)).isNull();
		assertThat(IcsValues.text(null, 500)).isNull();
		// Cut between the two halves of an emoji, the half is dropped rather than kept broken.
		assertThat(IcsValues.text("ab😀", 3)).isEqualTo("ab");
	}
}
