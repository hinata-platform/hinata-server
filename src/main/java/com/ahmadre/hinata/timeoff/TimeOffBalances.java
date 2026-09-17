package com.ahmadre.hinata.timeoff;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Period;

/**
 * How much leave a year is worth for one person: the leave year's bounds, and the arithmetic of
 * §§ 4 and 5 BUrlG.
 *
 * <p>Pure and Spring-free, like {@code timetracking.ApprovalPeriods}, and for the same reason:
 * three callers need the same answer — the grant, the request check in A2 and the report in A4 —
 * and two implementations of a rule this fiddly would disagree on exactly the cases nobody tests
 * by hand.
 *
 * <p><b>The rules, in the order the law states them.</b> The waiting period (§ 4 BUrlG) is six
 * months by default; before it is over somebody earns a twelfth of the annual leave per full month
 * of employment (§ 5 Abs. 1 lit. a). Somebody who leaves before the waiting period is over keeps
 * those twelfths (lit. b), and so does somebody who leaves in the first half of a year after it is
 * over (lit. c). Everybody else gets the year in full — including somebody who joined in March and
 * completed the waiting period in September, which is the case a naive "months employed ÷ 12"
 * would get wrong and short-change them for.
 *
 * <p><b>Fractions round the way § 5 Abs. 2 BUrlG says they do</b>, which is not the way rounding
 * usually goes: a fraction of at least half a day becomes a whole day, and a smaller one stays a
 * fraction. Five twelfths of twenty days is 8.33 and stays 8.33; seven twelfths is 11.67 and
 * becomes 12. Nothing here ever rounds down, because rounding down would be taking away leave the
 * law granted.
 *
 * <p>Everything is in thousandths of a working day ({@link TimeOffType#DAY}). Integers, so a
 * balance cannot drift by a rounding error into an argument nobody can settle.
 */
public final class TimeOffBalances {

	private TimeOffBalances() {
	}

	/** What a type says about how a year is earned. */
	public record Rules(int allowanceMilliDays, MonthDay anchor, int waitingPeriodMonths,
			boolean prorateOnJoin, boolean prorateOnLeave) {

		public static Rules of(TimeOffType type) {
			return new Rules(type.allowanceMilliDays(), type.yearAnchor(), type.waitingPeriodMonths(),
					type.prorateOnJoin(), type.prorateOnLeave());
		}
	}

	/** Why a year worked out the way it did. The screen says this in words; the test asserts it. */
	public enum Reason {
		/** The whole annual entitlement. */
		FULL,
		/** Twelfths, because the waiting period does not end inside this year (§ 5 Abs. 1 lit. a). */
		WAITING_PERIOD,
		/** Twelfths, because the employment ended before the waiting period was over (lit. b). */
		LEFT_IN_WAITING_PERIOD,
		/** Twelfths, because the employment ended in the first half of the year (lit. c). */
		LEFT_IN_FIRST_HALF,
		/** Nothing: the person was not employed during this leave year at all. */
		NOT_EMPLOYED
	}

	/** What one leave year is worth, and how it was arrived at. */
	public record Accrued(int milliDays, int monthsCounted, Reason reason) {
	}

	// --- the leave year ---------------------------------------------------------

	/** The first day of the leave year named [year]. The calendar year unless a type says otherwise. */
	public static LocalDate yearStart(int year, MonthDay anchor) {
		return anchor.atYear(year);
	}

	/** The last day of that leave year: the day before the next one starts. */
	public static LocalDate yearEnd(int year, MonthDay anchor) {
		return yearStart(year + 1, anchor).minusDays(1);
	}

	/**
	 * Which leave year a day belongs to.
	 *
	 * <p>Named by the year its <em>start</em> falls in, so a leave year anchored on 1 April 2026
	 * runs to 31 March 2027 and is called 2026 throughout. Any other convention would make "the
	 * 2026 balance" ambiguous in exactly the months people ask about it.
	 */
	public static int leaveYearOf(LocalDate date, MonthDay anchor) {
		return date.isBefore(yearStart(date.getYear(), anchor)) ? date.getYear() - 1 : date.getYear();
	}

	// --- what a year is worth ----------------------------------------------------

	/**
	 * What [year] grants somebody who joined on [hiredOn] and, where it is known, left on
	 * [leftOn]. Both may be null: an instance that keeps no joining date grants the year in full,
	 * which is the answer that cannot short-change anybody.
	 */
	public static Accrued accrue(Rules rules, LocalDate hiredOn, LocalDate leftOn, int year) {
		LocalDate start = yearStart(year, rules.anchor());
		LocalDate end = yearEnd(year, rules.anchor());
		LocalDate from = hiredOn == null || hiredOn.isBefore(start) ? start : hiredOn;
		LocalDate to = leftOn == null || leftOn.isAfter(end) ? end : leftOn;
		if (from.isAfter(to)) {
			return new Accrued(0, 0, Reason.NOT_EMPLOYED);
		}
		int months = fullMonths(from, to);
		if (hiredOn == null) {
			return new Accrued(rules.allowanceMilliDays(), 12, Reason.FULL);
		}
		LocalDate waitingOver = hiredOn.plusMonths(rules.waitingPeriodMonths());
		if (waitingOver.isAfter(end) && rules.prorateOnJoin()) {
			// § 5 Abs. 1 lit. a — the waiting period does not end inside this year.
			return new Accrued(twelfths(rules.allowanceMilliDays(), months), months,
					Reason.WAITING_PERIOD);
		}
		boolean leavesThisYear = leftOn != null && !leftOn.isAfter(end);
		if (leavesThisYear && rules.prorateOnLeave()) {
			if (leftOn.isBefore(waitingOver)) {
				// lit. b — gone before the waiting period was over.
				return new Accrued(twelfths(rules.allowanceMilliDays(), months), months,
						Reason.LEFT_IN_WAITING_PERIOD);
			}
			if (leftOn.isBefore(start.plusMonths(6))) {
				// lit. c — gone in the first half of the leave year.
				return new Accrued(twelfths(rules.allowanceMilliDays(), months), months,
						Reason.LEFT_IN_FIRST_HALF);
			}
		}
		// Everybody else, including somebody who joined in March and finished the waiting period
		// in September: the year in full. Twelfths here would take away leave § 4 already granted.
		return new Accrued(rules.allowanceMilliDays(), months, Reason.FULL);
	}

	/**
	 * How much of a year has been earned by [on], for a type that accrues monthly.
	 *
	 * <p>An annual type earns the whole year on its anchor day; a monthly one earns a twelfth per
	 * full month. Both are capped by what the year is worth in the first place, so somebody in
	 * their waiting period does not out-earn their entitlement in month eleven.
	 */
	public static int accruedBy(Accrued year, TimeOffType.Accrual accrual, MonthDay anchor,
			int leaveYear, LocalDate on) {
		if (accrual != TimeOffType.Accrual.MONTHLY) {
			return year.milliDays();
		}
		LocalDate start = yearStart(leaveYear, anchor);
		if (on.isBefore(start)) {
			return 0;
		}
		LocalDate end = yearEnd(leaveYear, anchor);
		LocalDate until = on.isAfter(end) ? end : on;
		int months = fullMonths(start, until);
		return Math.min(year.milliDays(), twelfths(year.milliDays(), months));
	}

	/**
	 * [months] twelfths of [allowanceMilliDays], rounded the way § 5 Abs. 2 BUrlG rounds.
	 *
	 * <p>Up to a whole day when the leftover is at least half a day, and left as a fraction when it
	 * is less. Never down: that would be taking away leave the law granted.
	 */
	public static int twelfths(int allowanceMilliDays, int months) {
		if (months <= 0 || allowanceMilliDays <= 0) {
			return 0;
		}
		if (months >= 12) {
			return allowanceMilliDays;
		}
		long raw = (long) allowanceMilliDays * months / 12L;
		long remainder = raw % TimeOffType.DAY;
		long rounded = remainder >= TimeOffType.DAY / 2 ? raw - remainder + TimeOffType.DAY : raw;
		return (int) Math.min(rounded, allowanceMilliDays);
	}

	/**
	 * Complete months between two days, both included.
	 *
	 * <p>A month counts when it was worked end to end: joining on the 15th makes that month a
	 * partial one, which is what "voller Monat" in § 5 Abs. 1 BUrlG means.
	 */
	public static int fullMonths(LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			return 0;
		}
		return (int) Math.min(12, Period.between(from, to.plusDays(1)).toTotalMonths());
	}
}
