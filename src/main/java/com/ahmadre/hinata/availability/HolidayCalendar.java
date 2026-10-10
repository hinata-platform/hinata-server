package com.ahmadre.hinata.availability;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A set of public holidays, kept by administrators: by hand, imported from an ICS feed, or
 * filled from the statutory rules of a region ({@link #rules}).
 *
 * <p>A calendar with rules or a feed fills each year on its own ({@link HolidayAutoFill}), once:
 * a year in {@link #syncedYears} is not filled again unasked, so a day an administrator removed
 * stays removed. People pick the calendar they follow in their working-time pattern; without a
 * pick, {@link #defaultCalendar}.
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

	/**
	 * The region whose statutory holidays fill this calendar, {@code DE} or {@code DE-BY}, or null.
	 * A calendar has rules or a feed, never both.
	 */
	private String rules;

	/**
	 * The calendar the platform made from its holiday region. It follows that setting until the
	 * organisation chooses another source for it; renaming it or editing its days keeps it following.
	 */
	private Boolean platformDefault;

	/** Years filled on their own already, see the class comment. */
	private List<Integer> syncedYears;

	/**
	 * The calendar for everybody who did not pick one. At most one is, and only that one carries the
	 * field, so the sparse index is a single entry every capacity read finds it by.
	 */
	@Indexed(name = "default_calendar", sparse = true)
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

	public boolean isPlatformDefault() {
		return Boolean.TRUE.equals(platformDefault);
	}

	/** Whether this calendar fills itself: from rules, or from a feed. */
	public boolean fillsItself() {
		return rules != null || source != null;
	}

	public boolean synced(int year) {
		return syncedYears != null && syncedYears.contains(year);
	}

	/** What one import did, per year it covered. */
	public record ImportSummary(int year, int added, int updated, int unchanged, int capped,
			boolean truncated) {
	}
}
