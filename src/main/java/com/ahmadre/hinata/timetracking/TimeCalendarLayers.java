package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.CapacityService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The layers the calendar draws beside a person's own entries: their absences, the holidays of
 * the calendar they follow, and the minutes they planned for each day (HIN-91).
 *
 * <p>A read for display and nothing else. This class and {@link TimeHintsService} are the only
 * places in the module that know availability, and no write path may know them
 * ({@code ModuleBoundaryTest}): a holiday, an absence or a day without planned hours is marked in
 * the calendar, and an entry on it is recorded like any other (R9).
 *
 * <p>The shapes use plain values, so the controller that serves them does not depend on
 * availability either.
 */
@Component
@RequiredArgsConstructor
public class TimeCalendarLayers {

	private final CapacityService capacity;

	public record Absence(String type, LocalDate from, LocalDate to, boolean halfDay) {
	}

	public record HolidayDay(LocalDate date, String name, boolean halfDay) {
	}

	/** {@code scheduledMinutes} holds every day of the window, 0 for a day without planned hours. */
	public record Layers(List<Absence> absences, List<HolidayDay> holidays, Map<LocalDate, Integer> scheduledMinutes) {
	}

	/** The layers of [user]'s own calendar from [from] to [to]. */
	Layers of(User user, LocalDate from, LocalDate to) {
		CapacityService.Window window = capacity.window(user.getId(), from, to);
		Map<LocalDate, Integer> scheduled = new LinkedHashMap<>();
		window.days().forEach(day -> scheduled.put(day.date(), day.minutes()));
		return new Layers(
				window.absences().stream()
						.map(mark -> new Absence(mark.type().name(), mark.from(), mark.to(), mark.halfDay()))
						.toList(),
				window.holidays().stream()
						.map(mark -> new HolidayDay(mark.date(), mark.name(), mark.halfDay()))
						.toList(),
				scheduled);
	}
}
