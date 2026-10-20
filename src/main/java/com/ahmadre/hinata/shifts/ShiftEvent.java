package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Something that needs people for a while: a trade-fair stand, a summer party, a board meeting, a
 * workshop rota. It holds the shifts people sign up for and owns the time zone their wall-clock
 * times are read in.
 *
 * <p>Flags are wrapper types on purpose. A document written before a flag existed has no field,
 * and a primitive would turn that absence into a mapping failure; the accessors below read a
 * missing value as its default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_events")
// The list of a project's events in a window, sorted by start: projectId is the equality (one
// value, or the visible projects as $in), startsAt the range and the sort, _id the tiebreaker of
// the page order, so the read needs no SORT stage.
@CompoundIndex(name = "project_start", def = "{'projectId': 1, 'startsAt': 1, '_id': 1}")
// HIN-161: one occurrence per series and day. A net under the materialisation, not its mechanism
// (that is the compare-and-set on the series' rev): only documents of a series are in it, and the
// series' future occurrences are read by seriesId with the day as the range.
@CompoundIndex(name = "series_day", def = "{'seriesId': 1, 'occurrenceDate': 1}", unique = true,
		partialFilter = "{'seriesId': {$type: 'string'}}")
public class ShiftEvent {

	@Id
	private String id;

	/** Set by the server from the route, never read from a request body. */
	private String projectId;

	/** No text index: there is no search over events. */
	private String title;

	private String description;

	private String location;

	private Instant startsAt;

	private Instant endsAt;

	/** IANA zone the event's wall-clock times are read in. Required. */
	private String timezone;

	@Builder.Default
	private Boolean cancelled = Boolean.FALSE;

	@Builder.Default
	private Boolean selfAssignable = Boolean.TRUE;

	private String createdBy;

	/** HIN-161: the series this event is an occurrence of; null for an event of its own. */
	private String seriesId;

	/** HIN-161: the occurrence's day in the series' zone; set exactly when {@link #seriesId} is. */
	private LocalDate occurrenceDate;

	/**
	 * HIN-161: changed on its own ("only this one"). It keeps {@link #seriesId} for the label and no
	 * longer follows changes to the series.
	 */
	private Boolean detached;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public boolean isCancelled() {
		return Boolean.TRUE.equals(cancelled);
	}

	public boolean isDetached() {
		return Boolean.TRUE.equals(detached);
	}

	/** An occurrence of a series, detached or not. */
	public boolean inSeries() {
		return seriesId != null;
	}

	/** Open for members to sign up unless somebody closed it. */
	public boolean isSelfAssignable() {
		return !Boolean.FALSE.equals(selfAssignable);
	}
}
