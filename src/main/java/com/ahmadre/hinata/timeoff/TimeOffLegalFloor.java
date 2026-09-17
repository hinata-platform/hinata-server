package com.ahmadre.hinata.timeoff;

/**
 * The statutory minimum leave for a working week, and whether an allowance clears it.
 *
 * <p>§ 3 Abs. 1 BUrlG grants 24 working days a year on a six-day week — four weeks. Fewer working
 * days in the week means proportionally fewer days of leave for the same four weeks off: twenty on
 * a five-day week, sixteen on a four-day week. Nothing else changes, which is why the whole rule is
 * <em>four times the working days per week</em>.
 *
 * <p>This <b>warns and never refuses</b>. There are constellations the arithmetic here does not
 * know about — somebody who joined in October and has a lawful part-year entitlement, an employer
 * granting unpaid leave beside a paid type, a working week that changed in July. Refusing a save
 * over a number this class computed from one week's pattern would block correct configurations and
 * teach an operator to work around the product. Saying "twenty days would be the minimum for a
 * five-day week" at the moment somebody types eighteen is what actually helps.
 *
 * <p>Statutory extras are deliberately outside it: five more days for a severely disabled employee
 * (§ 208 SGB IX), more for young workers (§ 19 JArbSchG), the rules around maternity protection
 * (§ 24 MuSchG). Each depends on a fact about the person which hinata does not store and should
 * not — those are special categories of personal data (Art. 9 DSGVO) and a project tool has no
 * business holding them. A keeper books the extra days with a reason, and the reason lives on the
 * booking rather than on the person.
 */
public final class TimeOffLegalFloor {

	/** § 3 Abs. 1 BUrlG: four weeks, whatever the week looks like. */
	public static final int WEEKS = 4;

	/** Working days in the six-day week the statute is written for. */
	public static final int STATUTORY_WEEK_DAYS = 6;

	/** The days the statute names for that week: 24. */
	public static final int STATUTORY_DAYS = WEEKS * STATUTORY_WEEK_DAYS;

	/**
	 * The week to assume where there is no person to ask about: five days, twenty days of leave.
	 *
	 * <p>For a screen that spans a page of people rather than standing in front of one — a keeper's
	 * list, the editor where a quota is typed. It is a figure to compare against, never one to
	 * decide with: whose week it really is, only that person's pattern says.
	 */
	public static final int STANDARD_WORKING_DAYS = 5;

	private TimeOffLegalFloor() {
	}

	/** The minimum for a week with [workingDaysPerWeek] working days, in thousandths of a day. */
	public static int minimumMilliDays(int workingDaysPerWeek) {
		int days = Math.clamp(workingDaysPerWeek, 0, 7);
		return days * WEEKS * TimeOffType.DAY;
	}

	/**
	 * Whether an allowance falls short of the minimum for this working week.
	 *
	 * <p>Only for a paid type of the vacation kind that carries a balance: unpaid leave, training
	 * days and time off in lieu are not statutory leave and have no floor to fall below.
	 */
	public static boolean fallsShort(TimeOffType type, int workingDaysPerWeek) {
		if (type.getKind() != TimeOffType.Kind.VACATION || !type.countsAgainstBalance()
				|| type.isUnlimited() || !Boolean.TRUE.equals(type.getPaid())) {
			return false;
		}
		return type.allowanceMilliDays() < minimumMilliDays(workingDaysPerWeek);
	}
}
