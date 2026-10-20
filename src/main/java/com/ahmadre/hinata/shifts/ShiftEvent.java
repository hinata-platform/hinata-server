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

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public boolean isCancelled() {
		return Boolean.TRUE.equals(cancelled);
	}

	/** Open for members to sign up unless somebody closed it. */
	public boolean isSelfAssignable() {
		return !Boolean.FALSE.equals(selfAssignable);
	}
}
