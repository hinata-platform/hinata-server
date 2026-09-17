package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.WorkingSchedule;
import com.ahmadre.hinata.availability.WorkingScheduleRepository;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * How many days a week somebody works. The one thing this module reads from availability.
 *
 * <p>Narrow on purpose. Absence management needs the shape of a working week to say what the
 * statutory minimum leave is (§ 3 Abs. 1 BUrlG: four of them), and it needs nothing else from
 * capacity — not the minutes, not the holidays, not who is away. A reader that handed out more
 * would be a second way to learn what {@code AvailabilityAccess} guards, which is why
 * {@code timetracking.ModuleBoundaryTest} names every class allowed to read that package and every
 * class allowed to call one.
 *
 * <p>It counts <em>days</em> and deliberately throws the minutes away. Somebody working four hours
 * on each of five days has a five-day week; the statute grants them the same four weeks of leave
 * as a full-timer, in days. Converting hours into an entitlement would be the mistake the package
 * documentation warns about — a balance counts working days, capacity counts minutes, and neither
 * is computed from the other.
 */
@Component
@RequiredArgsConstructor
public class TimeOffWorkWeek {

	/** What a week without a pattern is worth, before the instance default is consulted. */
	static final int FALLBACK_DAYS = 5;

	private final WorkingScheduleRepository schedules;
	private final HinataProperties properties;

	/**
	 * The working days in [userId]'s week as it stood on [on]: the pattern in force that day, or
	 * the instance default for somebody who has never saved one.
	 */
	public int workingDaysPerWeek(String userId, LocalDate on) {
		List<WorkingSchedule> history = schedules.findByUserIdOrderByValidFromDesc(userId,
				PageRequest.of(0, WorkingSchedule.HISTORY_MAX));
		return history.stream()
				.filter(pattern -> !pattern.getValidFrom().isAfter(on))
				.findFirst()
				.map(TimeOffWorkWeek::daysIn)
				.orElseGet(this::defaultDays);
	}

	private static int daysIn(WorkingSchedule pattern) {
		List<Integer> minutes = pattern.getMinutesPerWeekday();
		if (minutes == null || minutes.isEmpty()) {
			return FALLBACK_DAYS;
		}
		return (int) minutes.stream().filter(day -> day != null && day > 0).count();
	}

	private int defaultDays() {
		List<Integer> minutes = properties.getAvailability().getDefaultWeekdayMinutes();
		if (minutes == null || minutes.isEmpty()) {
			return FALLBACK_DAYS;
		}
		long days = minutes.stream().filter(day -> day != null && day > 0).count();
		return days == 0 ? FALLBACK_DAYS : (int) days;
	}
}
