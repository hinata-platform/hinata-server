package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A recurring event (HIN-161): a weekly on-call duty, a monthly consultation hour, a bar rota every
 * Friday. Planned once, it materialises as ordinary {@link ShiftEvent}s with ordinary
 * {@link Shift}s, so assigning, reminding, the calendar and the feed work on an occurrence without
 * knowing it belongs to a series.
 *
 * <p><b>Wall-clock times, not instants.</b> {@link #firstStart} and the templates' times are read in
 * {@link #timezone}, because "every Monday at 18:00" stays 18:00 across the change to summer time.
 * Every occurrence is computed afresh from its date, the wall-clock time and the zone, never by
 * adding an instant difference to another occurrence. They and the days are stored as ISO text,
 * never as a date the driver would read in the server's zone.
 *
 * <p><b>{@link #rev} is the lock.</b> Every write to a series (the nightly run, a split, a deletion)
 * starts its transaction with a compare-and-set on the rev it read; a second writer finds a
 * different rev and either retries on what the first one left or stops. The unique index on
 * {@code shift_events {seriesId, occurrenceDate}} is the net under it, not the mechanism: a
 * duplicate key would abort the whole transaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_series")
// The nightly run: only open series, by how far they reach.
@CompoundIndex(name = "open_horizon", def = "{'materializedUntil': 1}", partialFilter = "{'finished': false}")
// Clearing a deleted project.
@CompoundIndex(name = "project", def = "{'projectId': 1}")
public class ShiftSeries {

	@Id
	private String id;

	/** Compare-and-set for the nightly run, a split and a deletion. */
	@Version
	private Long rev;

	private String projectId;

	private String title;

	private String description;

	private String location;

	/** IANA zone the wall-clock times are read in. */
	private String timezone;

	/**
	 * Wall-clock start of the first occurrence; at most a year back and two years ahead. Stored as
	 * ISO text ({@code 2026-10-26T18:00}), like every wall-clock time and day of the module: the
	 * default mapping would turn it into an instant in the server's zone, and a server moved to
	 * another zone would read a different time.
	 */
	@ValueConverter(ShiftWallClock.class)
	private LocalDateTime firstStart;

	/** How long one occurrence lasts, on the wall clock; at most fourteen days. */
	private Integer durationMinutes;

	/** RFC 5545, the subset {@code IcsParser.expand} takes. */
	private String rrule;

	/** Days left out, written only by the server; at most 366, the past ones trimmed at night. */
	@Builder.Default
	@ValueConverter(ShiftWallClock.class)
	private List<LocalDate> exdates = new ArrayList<>();

	/** The shifts every occurrence gets; at most 50. */
	@Builder.Default
	private List<ShiftTemplate> shifts = new ArrayList<>();

	@Builder.Default
	private Boolean selfAssignable = Boolean.TRUE;

	/** The last day occurrences exist for. ISO text; ISO dates sort as the days do. */
	@ValueConverter(ShiftWallClock.class)
	private LocalDate materializedUntil;

	/** UNTIL or COUNT reached, or the project gone: the nightly run passes it by. */
	@Builder.Default
	private Boolean finished = Boolean.FALSE;

	/** Null once the person's account is deleted. */
	private String createdBy;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public boolean isSelfAssignable() {
		return !Boolean.FALSE.equals(selfAssignable);
	}

	public boolean isFinished() {
		return Boolean.TRUE.equals(finished);
	}

	public int duration() {
		return durationMinutes == null ? 60 : durationMinutes;
	}

	public long revision() {
		return rev == null ? 0 : rev;
	}
}
