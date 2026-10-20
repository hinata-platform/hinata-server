package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * One shift of every occurrence of a {@link ShiftSeries}, in wall-clock times of the series' zone.
 * {@link #key} is stable for the template's life: a series change finds the occurrences' shifts by
 * it ({@code Shift.templateKey}), never by the title.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ShiftTemplate {

	private String key;

	private String title;

	/** The day within the occurrence, 0 for its first day. */
	private Integer dayOffset;

	private LocalTime start;

	/** At or before {@link #start} means the next day. */
	private LocalTime end;

	private Integer requiredHelpers;

	/** Null means no upper limit. */
	private Integer maxHelpers;

	public int offset() {
		return dayOffset == null ? 0 : dayOffset;
	}

	public int required() {
		return requiredHelpers == null ? 1 : requiredHelpers;
	}
}
