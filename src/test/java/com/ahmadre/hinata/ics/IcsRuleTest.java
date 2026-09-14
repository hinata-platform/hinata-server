package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsRuleTest {

	@Test
	void keepsWhatItCheckedAndLeavesUntilToTheParser() {
		IcsRule exchange = IcsRule.read("FREQ=WEEKLY;UNTIL=20260427T070000Z;INTERVAL=1;BYDAY=TU;WKST=MO", false);

		assertThat(exchange.until()).isEqualTo(new IcsRule.Until(null, LocalDateTime.of(2026, 4, 27, 7, 0), true));
		assertThat(exchange.until().instant(ZoneOffset.UTC.getRules())).isEqualTo(Instant.parse("2026-04-27T07:00:00Z"));
		assertThat(exchange.forRecur()).isEqualTo("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;WKST=MO");
		assertThat(exchange.expandable()).isTrue();
		assertThat(IcsRule.read("FREQ=YEARLY;COUNT=6", true).forRecur()).isEqualTo("FREQ=YEARLY;COUNT=6");
		assertThat(IcsRule.read("freq=monthly;byday=-1fr;", false).forRecur()).isEqualTo("FREQ=MONTHLY;BYDAY=-1FR");
	}

	@Test
	void anUntilDateAllowsItsWholeDay() {
		IcsRule.Until until = IcsRule.read("FREQ=DAILY;UNTIL=20260305", true).until();

		assertThat(until.day()).isEqualTo(LocalDate.of(2026, 3, 5));
		assertThat(until.instant(ZoneId.of("Europe/Berlin").getRules()))
				.isEqualTo(Instant.parse("2026-03-05T22:59:59.999999999Z"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "FREQ", "COUNT=3", "FREQ=SOMETIMES", "FREQ=DAILY;FREQ=WEEKLY",
			"FREQ=DAILY;COUNT=3;UNTIL=20260301", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=-1",
			"FREQ=DAILY;COUNT=99999999999", "FREQ=DAILY;INTERVAL=often", "FREQ=DAILY;BYHOUR=24",
			"FREQ=MONTHLY;BYMONTHDAY=0", "FREQ=MONTHLY;BYMONTHDAY=32", "FREQ=WEEKLY;BYDAY=1MO", "FREQ=MONTHLY;BYDAY=0MO",
			"FREQ=DAILY;UNTIL=someday", "FREQ=DAILY;WKST=XX", "FREQ=DAILY;X-NAME=1", "FREQ=DAILY;RSCALE=GREGORIAN" })
	void refusesWhatRfc5545DoesNotAllow(String rule) {
		assertThatThrownBy(() -> IcsRule.read(rule, false)).isInstanceOf(DateTimeException.class);
	}

	@Test
	void refusesTimesOfDayInASeriesOfDays() {
		assertThatThrownBy(() -> IcsRule.read("FREQ=DAILY;BYHOUR=9", true)).isInstanceOf(DateTimeException.class);
	}

	@Test
	void dropsRepeatedValuesSoTheyCostNothingExtra() {
		String mondays = String.join(",", Collections.nCopies(333, "MO"));

		assertThat(IcsRule.read("FREQ=WEEKLY;BYDAY=" + mondays, false).forRecur()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=+1MO,1MO,MO;BYMONTHDAY=05,5", false).forRecur())
				.isEqualTo("FREQ=MONTHLY;BYDAY=1MO,MO;BYMONTHDAY=5");
	}

	@Test
	void expandsTheRulesCalendarsActuallyWrite() {
		for (String rule : List.of("FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "FREQ=MONTHLY;BYDAY=2TU",
				"FREQ=MONTHLY;BYMONTHDAY=15", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
				"FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU", "FREQ=YEARLY;INTERVAL=2;BYMONTH=1;BYMONTHDAY=1",
				"FREQ=WEEKLY;UNTIL=20251203T180000Z;INTERVAL=1;BYDAY=MO,WE;WKST=SU")) {
			assertThat(IcsRule.read(rule, false).expandable()).as(rule).isTrue();
		}
	}

	@Test
	void readsButDoesNotExpandWhatWouldHurtTheEngine() {
		assertThat(IcsRule.read("FREQ=MINUTELY", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=HOURLY;COUNT=3", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=DAILY;INTERVAL=101", false).expandable()).isFalse();
		// Seven weekdays of a whole year are 371 candidates, however short the list looks.
		assertThat(IcsRule.read("FREQ=YEARLY;BYDAY=MO,TU,WE,TH,FR,SA,SU", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=YEARLY;BYMONTH=" + range(1, 12) + ";BYMONTHDAY=" + range(1, 31)
				+ ";BYHOUR=" + range(0, 23), false).expandable()).isFalse();
		// A month has at most five Mondays: a sixth position leaves every period empty.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=6", false).expandable()).isFalse();
	}

	@Test
	void cutsAHugeCount() {
		IcsRule rule = IcsRule.read("FREQ=DAILY;COUNT=2000000000", false);

		assertThat(rule.shortened()).isTrue();
		assertThat(rule.forRecur()).isEqualTo("FREQ=DAILY;COUNT=5000");
	}

	@Test
	void doesNotExpandPositionsNoPeriodReaches() {
		// Two hours on every day of a month make 62 candidates at most, although 70 are estimated.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYHOUR=0,1;BYSETPOS=62", false).expandable())
				.isTrue();
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYHOUR=0,1;BYSETPOS=63", false).expandable())
				.isFalse();
		// No month has a sixth Monday, also not in a yearly rule of months; a year has a 53rd.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=6MO", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=YEARLY;BYMONTH=1;BYDAY=6MO", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=YEARLY;BYDAY=53MO", false).expandable()).isTrue();
		// The fifth Monday comes in some months, the 53rd in some years.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=5", false).expandable()).isTrue();
		assertThat(IcsRule.read("FREQ=YEARLY;BYDAY=MO;BYSETPOS=53", false).expandable()).isTrue();
		assertThat(IcsRule.read("FREQ=YEARLY;BYDAY=MO;BYSETPOS=54", false).expandable()).isFalse();
		// Beside other lists a position is not risked.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYMONTHDAY=1,15;BYSETPOS=-1", false).expandable()).isFalse();
	}

	@Test
	void countsTheCandidatesTheWayTheEngineBuildsThem() {
		// ical4j expands BYDAY from every date BYWEEKNO names, across the whole year: fourteen
		// weeks of seven days build thousands of candidates, not 98.
		assertThat(IcsRule.read("FREQ=YEARLY;BYWEEKNO=" + range(1, 14) + ";BYDAY=MO,TU,WE,TH,FR,SA,SU", false)
				.expandable()).isFalse();
		// Friday the 13th and the first Tuesday after a Monday in November stay well within.
		assertThat(IcsRule.read("FREQ=MONTHLY;BYMONTHDAY=13;BYDAY=FR", false).expandable()).isTrue();
		assertThat(IcsRule.read("FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=2,3,4,5,6,7,8;BYDAY=TU", false).expandable())
				.isTrue();
	}

	@Test
	void doesNotExpandListsTheFrequencyHasNoUseFor() {
		assertThat(IcsRule.read("FREQ=WEEKLY;BYDAY=MO;BYMONTHDAY=13", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=MONTHLY;BYYEARDAY=100", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=DAILY;BYWEEKNO=20", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=YEARLY;BYWEEKNO=20", false).expandable()).isTrue();
	}

	private static String range(int from, int to) {
		return IntStream.rangeClosed(from, to).mapToObj(String::valueOf).collect(Collectors.joining(","));
	}
}
