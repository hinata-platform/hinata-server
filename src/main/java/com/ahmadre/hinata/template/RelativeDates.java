package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.RelativeDate;

import java.time.LocalDate;

/**
 * "Event date plus this offset is which day?" — the one place that question is answered.
 *
 * <p>Three callers must get the same answer and would otherwise be three implementations: the
 * preview a form shows while somebody types, the copy that turns a template into a project, and
 * the rewrite after an event date moves. Two of them agreeing is not the same as one of them
 * existing; two implementations differ on exactly one day of the year, and it is always a day
 * somebody already made plans around.
 *
 * <p>Pure on purpose: no Spring, no database, no clock. Whatever a {@code WORKING} offset needs to
 * know about holidays arrives as a {@link WorkdayCalendar}, so the arithmetic is testable against
 * a handful of dates and the real calendar is somebody else's problem.
 */
public final class RelativeDates {

	private RelativeDates() {
	}

	/**
	 * The day {@code offset} lands on, counted from {@code anchor}.
	 *
	 * <p>Null anchor gives null: a project without an event date has nothing to count from, and
	 * the offset stays on the issue until it does. That is a state the product has on purpose —
	 * a template's issues carry offsets and no dates at all — so it is answered here rather than
	 * refused.
	 *
	 * @param anchor   the project's event date, or null while it has none
	 * @param offset   the distance to count, or null for no offset
	 * @param calendar which days work, consulted only for a {@code WORKING} offset; null falls
	 *                 back to skipping weekends alone
	 * @return the resulting day, or null when either the anchor or the offset is absent
	 */
	public static LocalDate resolve(LocalDate anchor, RelativeDate offset, WorkdayCalendar calendar) {
		if (anchor == null || offset == null) {
			return null;
		}
		return switch (offset.basis()) {
			case CALENDAR -> anchor.plusDays(offset.days());
			case WORKING -> workdays(anchor, offset.days(),
					calendar == null ? WorkdayCalendar.WEEKENDS_ONLY : calendar);
		};
	}

	/**
	 * {@code count} working days away from {@code anchor}, forwards or backwards.
	 *
	 * <p>The anchor itself is never counted, whether or not it is a working day: "three working
	 * days before the event" means three days of work to be had before it, and an event on a
	 * Monday does not consume one of them. Zero therefore lands on the anchor, even when the
	 * anchor is a Sunday — the day of the event is the day of the event.
	 *
	 * <p>Stepping one day at a time rather than in arithmetic: holidays do not fall on a period,
	 * so there is no closed form. The walk is bounded all the same. A calendar that answered "no"
	 * to every day would otherwise spin forever, and the one this module ships cannot do that —
	 * a year holds at most {@code Holiday.PER_YEAR_MAX} holidays against some 260 weekdays — but
	 * the interface is open, and an unbounded loop behind a request is not a risk worth keeping
	 * for the sake of a line.
	 */
	private static LocalDate workdays(LocalDate anchor, int count, WorkdayCalendar calendar) {
		if (count == 0) {
			return anchor;
		}
		int step = count > 0 ? 1 : -1;
		int remaining = Math.abs(count);
		int scanned = 0;
		int limit = remaining * DAYS_PER_WORKDAY_BUDGET + CALENDAR_YEAR_SLACK;
		LocalDate date = anchor;
		while (remaining > 0) {
			if (++scanned > limit) {
				throw new IllegalStateException(
						"no working day found within " + limit + " days of " + anchor);
			}
			date = date.plusDays(step);
			if (calendar.isWorkday(date)) {
				remaining--;
			}
		}
		return date;
	}

	/**
	 * How many calendar days one working day may cost before the walk gives up. Seven is a week
	 * of holidays around every single working day, which no real calendar produces.
	 */
	private static final int DAYS_PER_WORKDAY_BUDGET = 7;

	/** Room for a run of closed days at the start, before the first working day is reached. */
	private static final int CALENDAR_YEAR_SLACK = 366;
}
