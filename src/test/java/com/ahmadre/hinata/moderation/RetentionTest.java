package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ReportCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long a decided case is kept: thirty days without an action; with one, the instance's
 * months, but never shorter than the time for an appeal.
 */
class RetentionTest {

	private static final Instant DECIDED = Instant.parse("2026-03-31T10:00:00Z");

	private static Decision decided(Decision.Outcome outcome) {
		return new Decision(outcome, null, Decision.Ground.RULES, ReportCategory.HARASSMENT, "facts", null, null,
				null, false, null, null, false, Decision.Basis.NOTICE, false, null, "mira", DECIDED, null, null, null);
	}

	@Test
	void aCaseWithoutAnActionIsKeptThirtyDays() {
		assertThat(Retention.afterDecision(decided(Decision.Outcome.NO_VIOLATION), DECIDED, 12))
				.isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
	}

	@Test
	void anActionIsKeptTheInstancesMonthsInTheCalendar() {
		assertThat(Retention.afterDecision(decided(Decision.Outcome.ACTION), DECIDED, 12))
				.isEqualTo(Instant.parse("2027-03-31T10:00:00Z"));
		// The last of a shorter month, not a day into the next one.
		assertThat(Retention.plusMonths(Instant.parse("2026-08-31T10:00:00Z"), 6))
				.isEqualTo(Instant.parse("2027-02-28T10:00:00Z"));
	}

	@Test
	void neverShorterThanTheTimeForAnAppeal() {
		assertThat(Retention.afterDecision(decided(Decision.Outcome.ACTION), DECIDED, 2))
				.isEqualTo(Retention.appealUntil(DECIDED))
				.isEqualTo(Instant.parse("2026-09-30T10:00:00Z"));
	}
}
