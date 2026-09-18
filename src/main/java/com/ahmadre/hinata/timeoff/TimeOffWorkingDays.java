package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.CapacityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a span costs one person: {@link TimeOffSpan} with the calendar filled in.
 *
 * <p>The arithmetic is next door and pure. This is the part that has to ask availability which
 * days that person works and which of them a public holiday takes, which is why it is its own
 * class with its own name: {@code timetracking.ModuleBoundaryTest} lists every class in this
 * module allowed to read that package, and a reader that is easy to name is a reader that is easy
 * to keep narrow.
 *
 * <p>It reads a window and throws most of it away. Capacity answers in minutes and also hands over
 * the absences it found; neither belongs in a balance, so neither leaves this class. What comes
 * out is days.
 */
@Component
@RequiredArgsConstructor
public class TimeOffWorkingDays {

	private final CapacityService capacity;

	/**
	 * What [from] to [to] is worth for [userId], with [first] and [last] the thousandths its edge
	 * days count for.
	 *
	 * <p>One window read, whatever the length of the span — the loop below walks days already in
	 * memory rather than asking again per day.
	 */
	public TimeOffSpan.Result of(String userId, LocalDate from, LocalDate to, int first, int last) {
		CapacityService.Window window = capacity.window(userId, from, to);

		Set<LocalDate> working = new HashSet<>();
		for (CapacityService.ScheduledDay day : window.days()) {
			if (day.minutes() > 0) {
				working.add(day.date());
			}
		}
		Map<LocalDate, Boolean> holidays = new HashMap<>();
		for (CapacityService.HolidayMark holiday : window.holidays()) {
			holidays.put(holiday.date(), holiday.halfDay());
		}
		return TimeOffSpan.of(from, to, working::contains, holidays, first, last);
	}
}
