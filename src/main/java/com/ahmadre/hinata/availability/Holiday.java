package com.ahmadre.hinata.availability;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One public holiday of one calendar.
 *
 * <p>A day, and unique per calendar: importing the same feed twice finds every day already there
 * and changes nothing, and a day added by hand is not duplicated by a feed that names it too.
 */
@Data
@Builder(toBuilder = true)
@Document("holidays")
@CompoundIndex(name = "calendar_date", def = "{'calendarId': 1, 'date': 1}", unique = true)
public class Holiday {

	public static final int NAME_MAX = 120;

	/** Most holidays one calendar holds in a year. */
	public static final int PER_YEAR_MAX = 100;

	public enum Source {
		MANUAL, IMPORT
	}

	@Id
	private String id;

	private String calendarId;

	private LocalDate date;

	private String name;

	/** Christmas Eve and New Year's Eve are half days in many places. */
	private Boolean halfDay;

	private Source source;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	public boolean isHalfDay() {
		return Boolean.TRUE.equals(halfDay);
	}
}
