package com.ahmadre.hinata.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

	@Test
	void minutesAtARateRoundHalfUpToTheCent() {
		// 7 minutes at 95.00 per hour: 11.0833… ⇒ 11.08
		assertThat(Money.amount(9_500, 7)).isEqualTo(1_108);
		// 1 minute at 0.30 per hour: 0.5 cent ⇒ 1 cent (half up)
		assertThat(Money.amount(30, 1)).isEqualTo(1);
		// a credit note rounds away from zero the same way
		assertThat(Money.cents(-30)).isEqualTo(-1);
		assertThat(Money.amount(12_000, 90)).isEqualTo(18_000);
	}

	@Test
	void aGroupIsRoundedOnceNotPerEntry() {
		long exact = 0;
		for (int i = 0; i < 30; i++) {
			exact += Money.centMinutes(9_500, 7);
		}
		// 30 × 7 minutes = 3.5 hours = 332.50, where 30 roundings of 11.08 would give 332.40
		assertThat(Money.cents(exact)).isEqualTo(33_250);
	}

	@Test
	void taxAndShares() {
		assertThat(Money.tax(10_000, 1_900)).isEqualTo(1_900);
		assertThat(Money.tax(1_005, 1_900)).isEqualTo(191);
		assertThat(Money.permille(1, 3)).isEqualTo(333);
		assertThat(Money.permille(5, 0)).isNull();
	}
}
