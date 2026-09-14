package com.ahmadre.hinata.ics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsRuleTest {

	@Test
	void keepsWhatItCheckedAndLeavesUntilToTheParser() {
		IcsRule exchange = IcsRule.read("FREQ=WEEKLY;UNTIL=20260427T070000Z;INTERVAL=1;BYDAY=TU;WKST=MO", false);

		assertThat(exchange.until()).isEqualTo("20260427T070000Z");
		assertThat(exchange.forRecur()).isEqualTo("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;WKST=MO");
		assertThat(exchange.expandable()).isTrue();
		assertThat(IcsRule.read("FREQ=YEARLY;COUNT=6", true).count()).isEqualTo(6);
		assertThat(IcsRule.read("freq=monthly;byday=-1fr;", false).forRecur()).isEqualTo("FREQ=MONTHLY;BYDAY=-1FR");
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
	void readsButDoesNotExpandWhatWouldHurtTheEngine() {
		assertThat(IcsRule.read("FREQ=MINUTELY", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=HOURLY;COUNT=3", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=DAILY;INTERVAL=101", false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=YEARLY;BYMONTH=" + range(1, 12) + ";BYMONTHDAY=" + range(1, 31)
				+ ";BYHOUR=" + range(0, 23), false).expandable()).isFalse();
		assertThat(IcsRule.read("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", false).expandable()).isTrue();
	}

	@Test
	void cutsAHugeCount() {
		IcsRule rule = IcsRule.read("FREQ=DAILY;COUNT=2000000000", false);

		assertThat(rule.count()).isEqualTo(IcsRule.MAX_COUNT);
		assertThat(rule.shortened()).isTrue();
		assertThat(rule.forRecur()).isEqualTo("FREQ=DAILY;COUNT=5000");
	}

	private static String range(int from, int to) {
		return IntStream.rangeClosed(from, to).mapToObj(String::valueOf).collect(Collectors.joining(","));
	}
}
