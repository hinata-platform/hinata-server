package com.ahmadre.hinata.template;

import java.time.LocalDate;

/**
 * Which days count as working days while a {@code WORKING} offset is counted.
 *
 * <p>A one-method interface so {@link RelativeDates} stays pure: the arithmetic can be tested
 * against a handful of dates without a database, a holiday calendar or the extended time-tracking
 * module, and the real implementation is free to read holidays wherever they live.
 *
 * <p>It answers about a <em>day</em>, never about a person. Nothing here knows who is away, and
 * that is deliberate: a deadline four working days before an event must not move because somebody
 * booked leave, and capacity data has no business in a project's schedule.
 */
@FunctionalInterface
public interface WorkdayCalendar {

	/** Weekends only: Saturday and Sunday are off, every other day works. */
	WorkdayCalendar WEEKENDS_ONLY = date -> switch (date.getDayOfWeek()) {
		case SATURDAY, SUNDAY -> false;
		default -> true;
	};

	/** Whether work happens on {@code date}. */
	boolean isWorkday(LocalDate date);
}
