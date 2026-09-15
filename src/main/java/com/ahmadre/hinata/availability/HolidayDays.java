package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.ics.IcsEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The holidays a calendar feed names for one year, as days.
 *
 * <p>Pure, like {@link Capacity}. Which events count:
 *
 * <ul>
 * <li><b>All-day events</b>, every day from the start up to the exclusive end, so a two-day event
 * is two holidays. An end on or before the start is read as the start alone.</li>
 * <li><b>Events from midnight to midnight</b> in their own zone. Exchange writes holidays that way,
 * {@code TZID=Central Standard Time} from 00:00 to 00:00 the next day, and not as dates. A start
 * equal to its end at midnight (Christmas Eve at Northwestern) is one day.</li>
 * <li>Nothing else: an event at a time of day is an appointment, not a holiday. Cancelled events
 * and events without a name are skipped.</li>
 * </ul>
 *
 * <p>Two events on one day make one holiday, named after the first. At most
 * {@link Holiday#PER_YEAR_MAX} days are kept, the earliest; the rest are counted as capped.
 */
final class HolidayDays {

	private HolidayDays() {
	}

	record Day(LocalDate date, String name) {
	}

	record Result(List<Day> days, int capped) {
	}

	static Result of(Collection<IcsEvent> events, int year) {
		Map<LocalDate, String> byDay = new TreeMap<>();
		for (IcsEvent event : events) {
			String name = nameOf(event);
			if (name == null || event.status() == IcsEvent.Status.CANCELLED) {
				continue;
			}
			LocalDate start;
			LocalDate end;
			if (event instanceof IcsEvent.AllDay allDay) {
				start = allDay.start();
				end = allDay.end() == null || !allDay.end().isAfter(start) ? start.plusDays(1) : allDay.end();
			}
			else if (event instanceof IcsEvent.Timed timed) {
				LocalDateTime from = LocalDateTime.ofInstant(timed.start(), timed.zone());
				LocalDateTime until = LocalDateTime.ofInstant(timed.end(), timed.zone());
				if (!from.toLocalTime().equals(LocalTime.MIDNIGHT) || !until.toLocalTime().equals(LocalTime.MIDNIGHT)
						|| until.isBefore(from)) {
					continue;
				}
				start = from.toLocalDate();
				end = until.isAfter(from) ? until.toLocalDate() : start.plusDays(1);
			}
			else {
				continue;
			}
			// Clamped to the year first: a feed can send an event from the year 1 to next year, and
			// walking that day by day would cost millions of steps after the parser's budget is spent.
			LocalDate first = start.isBefore(LocalDate.of(year, 1, 1)) ? LocalDate.of(year, 1, 1) : start;
			LocalDate stop = end.isAfter(LocalDate.of(year + 1, 1, 1)) ? LocalDate.of(year + 1, 1, 1) : end;
			for (LocalDate day = first; day.isBefore(stop); day = day.plusDays(1)) {
				byDay.putIfAbsent(day, name);
			}
		}
		List<Day> days = byDay.entrySet().stream()
				.map(entry -> new Day(entry.getKey(), entry.getValue()))
				.toList();
		if (days.size() <= Holiday.PER_YEAR_MAX) {
			return new Result(days, 0);
		}
		return new Result(days.subList(0, Holiday.PER_YEAR_MAX), days.size() - Holiday.PER_YEAR_MAX);
	}

	private static String nameOf(IcsEvent event) {
		String summary = event.summary();
		if (summary == null || summary.isBlank()) {
			return null;
		}
		String name = summary.strip();
		return name.length() <= Holiday.NAME_MAX ? name : name.substring(0, Holiday.NAME_MAX);
	}
}
