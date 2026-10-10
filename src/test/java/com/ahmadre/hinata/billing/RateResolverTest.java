package com.ahmadre.hinata.billing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateResolverTest {

	private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

	private static BillingRate rate(BillingRate.Scope scope, String scopeId, String secondaryId, long cents,
			LocalDate from, LocalDate to) {
		return BillingRate.builder().kind(BillingRate.Kind.BILLABLE).scope(scope).scopeId(scopeId)
				.secondaryId(secondaryId).amountCents(cents).validFrom(from).validTo(to)
				.createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
	}

	private static RateResolver.Facts facts(LocalDate day) {
		return new RateResolver.Facts("ann", "p1", "i1", day, List.of("t1", "t2"));
	}

	private static final LocalDate EARLY = LocalDate.of(2020, 1, 1);

	@Test
	void theMostSpecificTargetWins() {
		List<BillingRate> all = List.of(
				rate(BillingRate.Scope.DEFAULT, null, null, 1_000, EARLY, null),
				rate(BillingRate.Scope.TEAM, "t1", null, 2_000, EARLY, null),
				rate(BillingRate.Scope.PROJECT, "p1", null, 3_000, EARLY, null),
				rate(BillingRate.Scope.USER, "ann", null, 4_000, EARLY, null),
				rate(BillingRate.Scope.PROJECT_MEMBER, "p1", "ann", 5_000, EARLY, null),
				rate(BillingRate.Scope.ISSUE, "i1", null, 6_000, EARLY, null));

		for (int dropped = 0; dropped < all.size(); dropped++) {
			RateResolver resolver = RateResolver.of(all.subList(0, all.size() - dropped));
			assertThat(resolver.resolve(facts(DAY)).getAmountCents())
					.isEqualTo(all.get(all.size() - 1 - dropped).getAmountCents());
		}
	}

	@Test
	void aRateChangeInTheMiddleOfAPeriodValuesEachDayWithItsOwnRate() {
		RateResolver resolver = RateResolver.of(List.of(
				rate(BillingRate.Scope.PROJECT, "p1", null, 8_000, EARLY, LocalDate.of(2026, 9, 14)),
				rate(BillingRate.Scope.PROJECT, "p1", null, 9_500, LocalDate.of(2026, 9, 15), null)));

		assertThat(resolver.resolve(facts(LocalDate.of(2026, 9, 14))).getAmountCents()).isEqualTo(8_000);
		assertThat(resolver.resolve(facts(LocalDate.of(2026, 9, 15))).getAmountCents()).isEqualTo(9_500);
	}

	@Test
	void aPlannedRateIsNotUsedBeforeItStarts() {
		RateResolver resolver = RateResolver.of(List.of(
				rate(BillingRate.Scope.USER, "ann", null, 7_000, LocalDate.of(2027, 1, 1), null),
				rate(BillingRate.Scope.DEFAULT, null, null, 5_000, EARLY, null)));

		assertThat(resolver.resolve(facts(DAY)).getAmountCents()).isEqualTo(5_000);
		assertThat(resolver.resolve(facts(LocalDate.of(2027, 1, 1))).getAmountCents()).isEqualTo(7_000);
	}

	@Test
	void aCorrectionBackwardsReachesTheDaysBeforeTheOldStart() {
		RateResolver before = RateResolver.of(List.of(
				rate(BillingRate.Scope.PROJECT, "p1", null, 8_000, LocalDate.of(2026, 9, 1), null)));
		RateResolver after = RateResolver.of(List.of(
				rate(BillingRate.Scope.PROJECT, "p1", null, 8_000, LocalDate.of(2026, 8, 1), null)));

		assertThat(before.resolve(facts(LocalDate.of(2026, 8, 20)))).isNull();
		assertThat(after.resolve(facts(LocalDate.of(2026, 8, 20))).getAmountCents()).isEqualTo(8_000);
	}

	@Test
	void ofTwoTeamRatesTheMoreRecentlyStartedOneApplies() {
		RateResolver resolver = RateResolver.of(List.of(
				rate(BillingRate.Scope.TEAM, "t1", null, 2_000, LocalDate.of(2026, 1, 1), null),
				rate(BillingRate.Scope.TEAM, "t2", null, 2_500, LocalDate.of(2026, 6, 1), null)));

		assertThat(resolver.resolve(facts(DAY)).getAmountCents()).isEqualTo(2_500);
		assertThat(resolver.resolve(facts(LocalDate.of(2026, 3, 1))).getAmountCents()).isEqualTo(2_000);
	}

	@Test
	void nothingCoversADayWithoutARate() {
		assertThat(RateResolver.of(List.of()).resolve(facts(DAY))).isNull();
	}
}
