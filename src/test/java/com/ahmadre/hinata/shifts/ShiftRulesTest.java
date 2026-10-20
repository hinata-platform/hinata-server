package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.UserZones;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rules 1 to 7 of HIN-43 as plain functions. */
class ShiftRulesTest {

	private static final Instant NINE = Instant.parse("2026-11-02T08:00:00Z");

	private static ShiftEvent event(Instant start, Instant end) {
		return ShiftEvent.builder().id("e").startsAt(start).endsAt(end).timezone("Europe/Berlin").build();
	}

	@Test
	void rule1TheEndComesAfterTheStart() {
		assertThatThrownBy(() -> ShiftRules.eventSpan(NINE, NINE)).hasMessage("error.shifts.endBeforeStart");
		assertThatThrownBy(() -> ShiftRules.span(NINE, NINE.minusSeconds(1))).hasMessage("error.shifts.endBeforeStart");
		assertThatThrownBy(() -> ShiftRules.span(null, NINE)).hasMessage("error.shifts.timesRequired");
		assertThatCode(() -> ShiftRules.span(NINE, NINE.plusSeconds(60))).doesNotThrowAnyException();
	}

	@Test
	void rule2AShiftStaysInsideItsEvent() {
		ShiftEvent event = event(NINE, NINE.plus(Duration.ofHours(10)));
		assertThatCode(() -> ShiftRules.shiftSpan(NINE, NINE.plus(Duration.ofHours(10)), event)).doesNotThrowAnyException();
		assertThatThrownBy(() -> ShiftRules.shiftSpan(NINE.minusSeconds(60), NINE.plusSeconds(3600), event))
				.hasMessage("error.shifts.shiftOutsideEvent");
		// Only the end beyond the event, the case of a patch that changes nothing else.
		assertThatThrownBy(() -> ShiftRules.shiftSpan(NINE, NINE.plus(Duration.ofHours(11)), event))
				.hasMessage("error.shifts.shiftOutsideEvent");
	}

	@Test
	void rules3And4CountPeopleSensibly() {
		assertThatCode(() -> ShiftRules.helpers(3, null)).doesNotThrowAnyException();
		assertThatCode(() -> ShiftRules.helpers(3, 3)).doesNotThrowAnyException();
		assertThatThrownBy(() -> ShiftRules.helpers(3, 2)).hasMessage("error.shifts.maxBelowRequired");
		assertThatThrownBy(() -> ShiftRules.helpers(0, null)).hasMessage("error.shifts.helpersOutOfRange");
		assertThatThrownBy(() -> ShiftRules.helpers(501, null)).hasMessage("error.shifts.helpersOutOfRange");
		assertThatThrownBy(() -> ShiftRules.helpers(1, 501)).hasMessage("error.shifts.helpersOutOfRange");
	}

	@Test
	void rule6AZoneHasToBeOne() {
		assertThatThrownBy(() -> UserZones.normalize("Mars/Olympus")).hasMessage("error.user.invalidTimezone");
		assertThat(UserZones.normalize("Europe/Berlin")).isEqualTo("Europe/Berlin");
	}

	@Test
	void rule7Limits() {
		assertThatThrownBy(() -> ShiftRules.eventSpan(NINE, NINE.plus(Duration.ofDays(14)).plusSeconds(1)))
				.hasMessage("error.shifts.eventTooLong");
		assertThatCode(() -> ShiftRules.eventSpan(NINE, NINE.plus(Duration.ofDays(14)))).doesNotThrowAnyException();
		ShiftEvent long_ = event(NINE, NINE.plus(Duration.ofDays(3)));
		assertThatThrownBy(() -> ShiftRules.shiftSpan(NINE, NINE.plus(Duration.ofHours(24)).plusSeconds(1), long_))
				.hasMessage("error.shifts.shiftTooLong");
		assertThatThrownBy(() -> ShiftRules.title("x".repeat(121), true)).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.getMessageKey()).isEqualTo("error.shifts.textTooLong"));
		assertThat(ShiftRules.title("x".repeat(120), true)).hasSize(120);
		assertThatThrownBy(() -> ShiftRules.title("  ", true)).hasMessage("error.shifts.titleRequired");
		assertThat(ShiftRules.title("  ", false)).isNull();
		assertThatThrownBy(() -> ShiftRules.description("x".repeat(2001))).hasMessage("error.shifts.textTooLong");
		assertThatThrownBy(() -> ShiftRules.location("x".repeat(201))).hasMessage("error.shifts.textTooLong");
	}

	@Test
	void windowsAreBounded() {
		assertThatThrownBy(() -> ShiftRules.window(NINE, NINE, ShiftRules.WINDOW_MAX)).hasMessage("error.shifts.windowInvalid");
		assertThatThrownBy(() -> ShiftRules.window(NINE, NINE.plus(Duration.ofDays(63)), ShiftRules.WINDOW_MAX))
				.hasMessage("error.shifts.windowTooLarge");
		assertThat(ShiftRules.pageSize(1000)).isEqualTo(100);
	}

	@Test
	void wallClockTimeIsWrittenInTheEventZoneWithSeconds() {
		assertThat(ShiftRules.local(NINE, "Europe/Berlin")).isEqualTo("2026-11-02T09:00:00");
		assertThat(ShiftRules.local(Instant.parse("2026-07-01T07:30:00Z"), "Europe/Berlin")).isEqualTo("2026-07-01T09:30:00");
	}
}
