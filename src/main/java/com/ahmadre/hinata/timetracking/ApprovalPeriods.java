package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Which days a submission period is made of.
 *
 * <p>Pure and Spring-free, on the model of {@code team/TeamAccess}, because the
 * arithmetic has more than one reader and none of them may own it: the route
 * that lists periods for the timesheet, the check that a submission lands on the
 * configured grid, and the admin preview that shows an operator what their
 * choice will mean before they save it. Two implementations of "which days are
 * in March" would disagree on exactly one day a year and nobody would notice
 * until a payroll period was short.
 *
 * <p>The app computes <em>nothing</em> here. It reads
 * {@code GET /api/v1/time/approvals/periods} and renders what comes back — which
 * is why the label, too, is the server's ("March 2026", "CW 12", "1–15 March")
 * rather than something a client derives from a type name it half understands.
 *
 * <p>Nothing in this class knows a default rhythm. The policy is handed in; an
 * absent type is the caller's problem to resolve, and
 * {@link TimeTrackingSettings#approvalPeriod()} is where that resolution lives
 * (monthly, once, and nowhere else).
 */
public final class ApprovalPeriods {

	/**
	 * The most periods one question may be answered with.
	 *
	 * <p>A guard, not a rule the client is meant to meet: a daily rhythm over a
	 * year is 365 rows of nothing anybody reads, and the route that calls this
	 * asks for the handful of periods it is about to draw. It sits here rather
	 * than at the route because an unbounded loop over a span is the kind of
	 * thing a later caller inherits without noticing.
	 */
	public static final int MAX_PERIODS = 120;

	/** The longest span one submission may cover, grid or free. */
	public static final int MAX_PERIOD_DAYS = 92;

	private ApprovalPeriods() {
	}

	/**
	 * One period: the days it covers, and the rhythm it was cut from.
	 *
	 * <p>The type travels with the dates because a stored approval keeps it as a
	 * snapshot — an operator who switches from weekly to monthly must not turn
	 * last month's four submissions into something else.
	 */
	public record Period(LocalDate start, LocalDate end, TimePolicy.ApprovalPeriod type) {

		/** Whether {@code date} falls inside, both bounds included. */
		public boolean contains(LocalDate date) {
			return date != null && !date.isBefore(start) && !date.isAfter(end);
		}

		/** How many days it covers, both bounds included. */
		public int days() {
			return (int) (end.toEpochDay() - start.toEpochDay()) + 1;
		}
	}

	/**
	 * The period {@code date} belongs to.
	 *
	 * <p>Not for {@link TimePolicy.ApprovalPeriod#FREE}: there is no grid to land
	 * on, so the honest answer is that the question does not apply, and returning
	 * a made-up span would have the app draw a switcher for a rhythm that does not
	 * exist. Callers ask {@link #hasGrid} first.
	 *
	 * @throws IllegalArgumentException for FREE, or for a policy whose parameters
	 *     its type needs and does not have ({@code ApprovalPeriodConsistent}
	 *     refuses those on the way in, so reaching this is a bug and not input)
	 */
	public static Period periodFor(LocalDate date, TimeTrackingSettings.ApprovalPeriod policy) {
		TimePolicy.ApprovalPeriod type = typeOf(policy);
		return switch (type) {
			case WEEKLY -> {
				LocalDate start = date.with(TemporalAdjusters.previousOrSame(weekStart(policy)));
				yield new Period(start, start.plusDays(6), type);
			}
			case BIWEEKLY -> blockFrom(date, anchor(policy, type), 14, type);
			case CUSTOM_DAYS -> blockFrom(date, anchor(policy, type), days(policy), type);
			case SEMI_MONTHLY -> date.getDayOfMonth() <= 15
					? new Period(date.withDayOfMonth(1), date.withDayOfMonth(15), type)
					: new Period(date.withDayOfMonth(16),
							date.with(TemporalAdjusters.lastDayOfMonth()), type);
			case MONTHLY -> new Period(date.withDayOfMonth(1),
					date.with(TemporalAdjusters.lastDayOfMonth()), type);
			case QUARTERLY -> {
				LocalDate start = date.withDayOfMonth(1)
						.withMonth((date.getMonthValue() - 1) / 3 * 3 + 1);
				yield new Period(start, start.plusMonths(2)
						.with(TemporalAdjusters.lastDayOfMonth()), type);
			}
			case FREE -> throw new IllegalArgumentException("FREE has no grid");
		};
	}

	/**
	 * Every period that shares a day with {@code [from, to]}, oldest first.
	 *
	 * <p>Empty for FREE, for the same reason {@link #periodFor} refuses it: the
	 * spans that exist in a free rhythm are the ones people have already
	 * submitted, and those come from the collection rather than from arithmetic.
	 *
	 * <p>Capped at {@link #MAX_PERIODS}. The cap is a guard; the route that calls
	 * this bounds its window so a client never meets it.
	 */
	public static List<Period> periodsIn(LocalDate from, LocalDate to,
			TimeTrackingSettings.ApprovalPeriod policy) {
		if (from == null || to == null || to.isBefore(from) || !hasGrid(policy)) {
			return List.of();
		}
		List<Period> periods = new ArrayList<>();
		Period current = periodFor(from, policy);
		while (!current.start().isAfter(to) && periods.size() < MAX_PERIODS) {
			periods.add(current);
			current = periodFor(current.end().plusDays(1), policy);
		}
		return List.copyOf(periods);
	}

	/**
	 * The period before or after {@code period}, for the switcher's two arrows.
	 *
	 * <p>Derived by stepping one day past the edge and asking again, rather than
	 * by subtracting the length: a semi-monthly period is 13, 14, 15 or 16 days
	 * long, and a month is four different lengths, so length is not how you get
	 * to the neighbour.
	 */
	public static Period shift(Period period, int direction,
			TimeTrackingSettings.ApprovalPeriod policy) {
		LocalDate probe = direction < 0
				? period.start().minusDays(1)
				: period.end().plusDays(1);
		return periodFor(probe, policy);
	}

	/**
	 * Whether {@code [start, end]} is exactly one period of this rhythm.
	 *
	 * <p>True for FREE, which has no grid to be off — and that is the one answer
	 * here that is not the whole rule. A free submission still has to be inside
	 * {@link #MAX_PERIOD_DAYS} and not overlap one already filed, and both of
	 * those are the service's checks against the collection, which this function
	 * cannot see. Nothing may use this as the only gate on a submission.
	 */
	public static boolean matchesGrid(LocalDate start, LocalDate end,
			TimeTrackingSettings.ApprovalPeriod policy) {
		if (start == null || end == null || end.isBefore(start)) {
			return false;
		}
		if (!hasGrid(policy)) {
			return true;
		}
		Period period = periodFor(start, policy);
		return period.start().equals(start) && period.end().equals(end);
	}

	/** Whether this rhythm cuts the calendar into periods at all — false only for FREE. */
	public static boolean hasGrid(TimeTrackingSettings.ApprovalPeriod policy) {
		return typeOf(policy) != TimePolicy.ApprovalPeriod.FREE;
	}

	// --- the pieces a type needs ------------------------------------------------

	/**
	 * The block of {@code length} days containing {@code date}, counted from
	 * {@code anchor}.
	 *
	 * <p>{@link Math#floorDiv} rather than integer division, which is what makes
	 * an anchor in the future work: {@code -1 / 14} is 0 and would put yesterday
	 * in the same block as the anchor day, while {@code floorDiv(-1, 14)} is -1
	 * and puts it in the block before. An operator who anchors a rhythm on the
	 * first of next month has to be able to look at last week.
	 */
	private static Period blockFrom(LocalDate date, LocalDate anchor, int length,
			TimePolicy.ApprovalPeriod type) {
		long offset = date.toEpochDay() - anchor.toEpochDay();
		long block = Math.floorDiv(offset, length);
		LocalDate start = anchor.plusDays(block * length);
		return new Period(start, start.plusDays(length - 1L), type);
	}

	private static TimePolicy.ApprovalPeriod typeOf(TimeTrackingSettings.ApprovalPeriod policy) {
		if (policy == null || policy.type() == null) {
			throw new IllegalArgumentException("approval period has no type");
		}
		return policy.type();
	}

	/**
	 * Monday when nothing says otherwise — the resolver already defaults it, and
	 * this is the second line of that same answer rather than a policy of its own.
	 */
	private static DayOfWeek weekStart(TimeTrackingSettings.ApprovalPeriod policy) {
		return policy.weekStartsOn() != null ? policy.weekStartsOn() : DayOfWeek.MONDAY;
	}

	/**
	 * The day a counted rhythm begins.
	 *
	 * <p>Taken verbatim, and deliberately <em>not</em> snapped to
	 * {@code weekStartsOn}: an operator who picks a date for the first biweekly
	 * period to begin has made the more specific statement of the two, and moving
	 * it to the nearest Monday behind their back would shift every period they
	 * then preview. {@code weekStartsOn} belongs to WEEKLY, and the admin hint
	 * says so.
	 */
	private static LocalDate anchor(TimeTrackingSettings.ApprovalPeriod policy,
			TimePolicy.ApprovalPeriod type) {
		LocalDate anchor = policy.anchorDate();
		if (anchor == null) {
			throw new IllegalArgumentException(type + " needs an anchor date");
		}
		return anchor;
	}

	private static int days(TimeTrackingSettings.ApprovalPeriod policy) {
		Integer days = policy.days();
		if (days == null || days < 1) {
			throw new IllegalArgumentException("CUSTOM_DAYS needs a positive day count");
		}
		return days;
	}
}
