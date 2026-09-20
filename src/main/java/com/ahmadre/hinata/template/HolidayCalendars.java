package com.ahmadre.hinata.template;

import com.ahmadre.hinata.availability.Holiday;
import com.ahmadre.hinata.availability.HolidayService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns a project's chosen holiday calendar into the yes-or-no question {@link RelativeDates}
 * asks.
 *
 * <p>This is the module's one reach into {@code availability}, and it reaches for the narrowest
 * thing there: {@code holidaysOf(calendarId, year)}, which is a property of a <em>calendar</em>.
 * No working pattern, no capacity, no absence — nothing that belongs to a person. A deadline four
 * working days before an event must not move because somebody booked leave, so the question is
 * "does this country work on that day", never "is anybody there".
 *
 * <p>The days are loaded once per calendar year touched and held for the length of one
 * computation. A schedule rewrite resolves hundreds of offsets around the same event date, and
 * they all land in the same year or two; asking the database per issue would turn one request into
 * hundreds of identical queries.
 */
@Component
@RequiredArgsConstructor
public class HolidayCalendars {

	private final HolidayService holidays;

	/**
	 * The calendar to count a project's working-day offsets against.
	 *
	 * <p>Weekends alone when the project names no calendar, and weekends alone again when it
	 * names one that has since been deleted: a deadline is not the place to discover that an
	 * administrator removed a calendar, and refusing the whole request would make every issue in
	 * the project unsavable until somebody noticed. The documentation says out loud that without
	 * a calendar a holiday counts as an ordinary working day.
	 */
	public WorkdayCalendar of(Project project) {
		String calendarId = project == null ? null : project.getWorkdayCalendarId();
		if (calendarId == null || calendarId.isBlank()) {
			return WorkdayCalendar.WEEKENDS_ONLY;
		}
		return new CachingCalendar(calendarId);
	}

	/**
	 * Weekends plus the holidays of one calendar, with each year fetched at most once.
	 *
	 * <p>Not a shared cache: an instance lives for one computation, so an administrator who adds
	 * a holiday sees it on the next request rather than after some expiry nobody can predict.
	 */
	private final class CachingCalendar implements WorkdayCalendar {

		private final String calendarId;
		private final Set<Integer> loaded = new HashSet<>();
		private final Set<LocalDate> days = new HashSet<>();

		private CachingCalendar(String calendarId) {
			this.calendarId = calendarId;
		}

		@Override
		public boolean isWorkday(LocalDate date) {
			if (!WorkdayCalendar.WEEKENDS_ONLY.isWorkday(date)) {
				return false;
			}
			load(date.getYear());
			return !days.contains(date);
		}

		private void load(int year) {
			if (!loaded.add(year)) {
				return;
			}
			try {
				for (Holiday holiday : holidays.holidaysOf(calendarId, year)) {
					// A half day is still a day work happens on, so it counts. Christmas Eve
					// closing at noon does not make a deadline a day later.
					if (!Boolean.TRUE.equals(holiday.getHalfDay())) {
						days.add(holiday.getDate());
					}
				}
			}
			catch (ApiException gone) {
				// The calendar was deleted, or the year is outside the range the service keeps.
				// Either way the answer is the one a project without a calendar gets: weekends
				// are off and every other day works.
			}
		}
	}
}
