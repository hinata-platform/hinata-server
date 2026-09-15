package com.ahmadre.hinata.availability;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * How many minutes somebody plans to work on each weekday, from a day on.
 *
 * <p>A new pattern is a new document and the old one stays: capacity for last March is computed
 * with the pattern that applied last March. The pattern for a day is the one with the latest
 * {@link #validFrom} on or before it; before the first one, the instance default applies.
 */
@Data
@Builder(toBuilder = true)
@Document("working_schedules")
@CompoundIndex(name = "user_valid_from", def = "{'userId': 1, 'validFrom': -1}", unique = true)
public class WorkingSchedule {

	/** Longest a working day can be. */
	public static final int DAY_MINUTES_MAX = 24 * 60;

	/** Most patterns one person keeps. A change of hours a week for a year is still far below. */
	public static final int HISTORY_MAX = 50;

	@Id
	private String id;

	private String userId;

	private LocalDate validFrom;

	/** Seven entries, Monday first, each 0 to {@link #DAY_MINUTES_MAX}. */
	private List<Integer> minutesPerWeekday;

	/** The holiday calendar this person follows, or null for the instance default. */
	private String holidayCalendarId;

	/** Who saved it: the person, or an administrator on their behalf. */
	private String createdBy;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	/** The planned minutes on [day], 0 for a weekday the pattern does not name. */
	public int minutesOn(DayOfWeek day) {
		return minutesOn(minutesPerWeekday, day);
	}

	static int minutesOn(List<Integer> minutesPerWeekday, DayOfWeek day) {
		if (minutesPerWeekday == null || minutesPerWeekday.size() <= day.ordinal()) {
			return 0;
		}
		Integer minutes = minutesPerWeekday.get(day.ordinal());
		return minutes == null ? 0 : minutes;
	}
}
