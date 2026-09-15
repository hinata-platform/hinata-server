package com.ahmadre.hinata.availability;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A set of public holidays, kept by administrators: by hand, or imported from an ICS feed.
 *
 * <p>No country logic in code. A region's holidays are whatever its calendar says, so a
 * Bundesland, a canton or a company's own closing days are all the same thing. People pick the
 * calendar they follow in their working-time pattern; without a pick, {@link #defaultCalendar}.
 *
 * <p>The feed address is a credential in all but name and is stored encrypted, bound to this
 * calendar ({@code "holiday-calendar:" + id}). Only its host is ever shown.
 */
@Data
@Builder(toBuilder = true)
@Document("holiday_calendars")
public class HolidayCalendar {

	public static final int NAME_MAX = 80;
	public static final int REGION_MAX = 80;

	/** Most calendars an instance keeps. Regions, not people. */
	public static final int COUNT_MAX = 100;

	public enum ImportState {
		RUNNING, DONE, FAILED
	}

	@Id
	private String id;

	private String name;

	private String region;

	/** The ICS address, encrypted, or null for a calendar kept by hand. */
	private String source;

	/** The host of {@link #source}, the only part of it anybody sees. */
	private String sourceHost;

	/** The calendar for everybody who did not pick one. At most one is. */
	private Boolean defaultCalendar;

	/** Validators from the last fetch, sent with the next one. */
	private String etag;
	private String lastModified;

	private ImportState importState;
	private Instant importStartedAt;
	private Instant lastImportedAt;

	/** The {@code error.*} key of the last failed import, and the number it names (a status, a line). */
	private String lastImportError;
	private Integer lastImportErrorArg;

	private ImportSummary lastImport;

	private String createdBy;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	public boolean isDefaultCalendar() {
		return Boolean.TRUE.equals(defaultCalendar);
	}

	/** What one import did, per year it covered. */
	public record ImportSummary(int year, int added, int updated, int unchanged, int capped,
			boolean truncated) {
	}
}
