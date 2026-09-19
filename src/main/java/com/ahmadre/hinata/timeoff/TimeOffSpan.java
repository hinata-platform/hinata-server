package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;

import java.time.LocalDate;
import java.util.Map;

/**
 * What a span of dates is worth in working days. Pure, Spring-free, and the only implementation.
 *
 * <p>Three readers ask this question and must never disagree: the form that previews a request
 * while somebody is still picking dates, the submission that freezes the answer onto the request,
 * and the booking that takes it off a balance. Two implementations would be two implementations
 * that are wrong on exactly one day a year, which is the day somebody notices.
 *
 * <p><b>It counts days, never minutes.</b> A day the pattern gives work is worth a whole day,
 * however many hours that is: § 3 BUrlG grants leave in working days, and somebody who works four
 * hours on each of five days gets the same four weeks as a full-timer. The minutes belong to
 * capacity and stay there — see the package documentation.
 *
 * <p><b>A day nobody works is worth nothing.</b> Weekends, the days a part-time pattern leaves
 * empty and public holidays all fall out of the count, which is why a week off over Easter costs
 * fewer days than a week off in July. That is § 3 Abs. 1 and § 7 BUrlG rather than a convenience:
 * a holiday that fell inside leave was never leave.
 *
 * <p><b>The answer is frozen, not recomputed.</b> Whoever submits a request stores the number this
 * returns. A pattern that changes afterwards — somebody moving from five days to four — does not
 * reinterpret a decision that has already been made, in either direction. Re-deriving it on every
 * read would mean a balance that silently moved while nobody did anything, and a person who could
 * not say why.
 */
public final class TimeOffSpan {

	/** One whole working day. Thousandths, so a half day is 500 and toggl's 0.375 is 375. */
	public static final int DAY = TimeOffType.DAY;

	/** The longest span a single request may cover, so one request cannot sweep a decade. */
	public static final int SPAN_DAYS_MAX = 366;

	private TimeOffSpan() {
	}

	/**
	 * The breakdown of a span: what it costs, and enough of why for a person to read it back.
	 *
	 * <p>[workingDays] and [holidays] exist so the form can say "seven working days, one public
	 * holiday" rather than only a total. A number on its own invites the suspicion that something
	 * was miscounted; the same number beside its reason does not.
	 */
	public record Result(int milliDays, int workingDays, int holidays, int daysOff) {
	}

	/**
	 * What [from] to [to] costs, given which days [working] gives work and which of them
	 * [holidays] takes away (the value says whether the holiday is a half day).
	 *
	 * <p>Both ends are inclusive, and a single-day span uses [first] alone: asking for a half day
	 * on one date must not come to a whole one because the same date is also the last day.
	 */
	public static Result of(LocalDate from, LocalDate to, java.util.function.Predicate<LocalDate> working,
			Map<LocalDate, Boolean> holidays, int first, int last) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.timeOff.spanInvalid");
		}
		if (from.plusDays(SPAN_DAYS_MAX - 1L).isBefore(to)) {
			throw ApiException.badRequest("error.timeOff.spanTooLong", SPAN_DAYS_MAX);
		}
		int total = 0;
		int workingDays = 0;
		int holidayCount = 0;
		int off = 0;
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			if (!working.test(day)) {
				off++;
				continue;
			}
			Boolean halfHoliday = holidays.get(day);
			int worth = DAY;
			if (halfHoliday != null) {
				holidayCount++;
				// A half holiday leaves half a working day standing, and that half is leave.
				// A whole one leaves nothing — § 3 Abs. 1 BUrlG counts working days, and a day
				// nobody works is not one.
				worth = Boolean.TRUE.equals(halfHoliday) ? DAY / 2 : 0;
			}
			if (worth == 0) {
				continue;
			}
			// The edges may be partial, and never more than the day itself is worth: half a day
			// of leave on a half holiday is a quarter of a day, not a half.
			int portion = day.equals(from) ? first : day.equals(to) ? last : DAY;
			worth = Math.min(worth, Math.round(worth * (portion / (float) DAY)));
			if (worth <= 0) {
				continue;
			}
			total += worth;
			workingDays++;
		}
		return new Result(total, workingDays, holidayCount, off);
	}
}
