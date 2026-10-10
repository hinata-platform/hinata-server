package com.ahmadre.hinata.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts in cents: what minutes at an hourly rate come to.
 *
 * <p>Whole cents in a {@code long}, never a {@code double}: a sum of a year of entries must come
 * out the same on every machine and in every report. Rounded half up ("kaufmännisch") once, at the
 * end — a line or a group adds up {@code rate × minutes} exactly and divides by sixty last, so
 * thirty entries of seven minutes are not thirty roundings.
 */
public final class Money {

	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

	private Money() {
	}

	/** {@code rateCents × minutes}, the exact value of minutes at a rate, in cent-minutes. */
	public static long centMinutes(long rateCents, long minutes) {
		return Math.multiplyExact(rateCents, minutes);
	}

	/** Cent-minutes as cents, rounded half up (away from zero for a negative amount). */
	public static long cents(long centMinutes) {
		return BigDecimal.valueOf(centMinutes).divide(SIXTY, 0, RoundingMode.HALF_UP).longValueExact();
	}

	/** What [minutes] at [rateCents] per hour come to, in cents. */
	public static long amount(long rateCents, long minutes) {
		return cents(centMinutes(rateCents, minutes));
	}

	/** [netCents] at a tax rate in basis points (1900 = 19 %), rounded half up. */
	public static long tax(long netCents, int basisPoints) {
		return BigDecimal.valueOf(netCents).multiply(BigDecimal.valueOf(basisPoints))
				.divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
	}

	/** [part] of [whole] in permille, rounded half up; null when there is no whole. */
	public static Integer permille(long part, long whole) {
		if (whole == 0) {
			return null;
		}
		return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(1000))
				.divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).intValueExact();
	}
}
