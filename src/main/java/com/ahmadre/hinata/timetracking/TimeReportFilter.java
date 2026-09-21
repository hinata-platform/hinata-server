package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * What a time report asks for (HIN-93): a window and the narrowing a reader chose.
 *
 * <p>Every report, export, saved report and scheduled mail reads through one of these, and
 * {@link #of} is the one place a request's values are checked — so a saved report that is run
 * a year later meets the same limits as the request that created it.
 *
 * @param description a word the description must contain, case-insensitive, or null. Only
 *                    with a window, which every report has, so a text search never reads a
 *                    career of entries.
 * @param approval    the approval states an entry's period may be in, empty for any
 * @param rounding    how each entry is folded before it is added up; the policy's default when
 *                    the caller names none
 * @param zone        the clock the report's start and end times are written on. Days are never
 *                    shifted by it: an entry's day is the day it was filed for.
 */
public record TimeReportFilter(LocalDate from, LocalDate to, List<String> projectIds, List<String> userIds,
		List<String> teamIds, List<String> tags, Boolean billable, List<String> activities, String description,
		Set<Approval> approval, TimeTrackingSettings.Rounding rounding, ZoneId zone) {

	/** Longest window a report covers: a year and a day, for leap years. */
	public static final int MAX_DAYS = 366;

	/** Most values one list filter takes; beyond it the $in stops being cheap. */
	public static final int MAX_VALUES = 100;

	/** Longest description search, in characters. */
	public static final int MAX_DESCRIPTION = 100;

	/**
	 * The state of the period an entry sits in for its person and project. {@code OPEN} is no
	 * submission, or a withdrawn one — the entry can still be changed by its owner.
	 */
	public enum Approval {
		OPEN, SUBMITTED, APPROVED, REJECTED
	}

	/**
	 * A checked filter: the window ascending, storable and at most {@link #MAX_DAYS}, every list
	 * at most {@link #MAX_VALUES} long without blanks, the description trimmed and bounded, and
	 * a zone that exists (the organization's, or UTC, when not).
	 */
	public static TimeReportFilter of(LocalDate from, LocalDate to, List<String> projectIds, List<String> userIds,
			List<String> teamIds, List<String> tags, Boolean billable, List<String> activities, String description,
			Set<Approval> approval, TimePolicy.Rounding roundingMode, Integer roundingIncrement,
			TimeTrackingSettings.Rounding policyRounding, String zone, ZoneId fallbackZone) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.time.report.invalidRange");
		}
		TimeTrackingService.assertStorable(from, to, "error.time.report.invalidRange");
		if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw ApiException.badRequest("error.time.report.invalidRange");
		}
		String text = description == null ? null : description.strip();
		if (text != null && text.length() > MAX_DESCRIPTION) {
			throw ApiException.badRequest("error.time.report.descriptionTooLong", MAX_DESCRIPTION);
		}
		return new TimeReportFilter(from, to, values(projectIds), values(userIds), values(teamIds), values(tags),
				billable, values(activities), text == null || text.isEmpty() ? null : text,
				approval == null ? Set.of() : Set.copyOf(approval),
				roundingMode == null ? policyRounding
						: new TimeTrackingSettings.Rounding(roundingMode,
								roundingIncrement == null ? 1 : Math.clamp(roundingIncrement, 1, 240)),
				zone(zone, fallbackZone));
	}

	/**
	 * Whether the filter reads individual people: a person or a team named, a description
	 * searched, an approval state asked about. Each of those narrows a project's total down to
	 * somebody, so they are answered over the entries whose people the reader may see, never over
	 * a project's aggregate.
	 */
	public boolean readsPeople() {
		return !userIds.isEmpty() || !teamIds.isEmpty() || description != null || !approval.isEmpty();
	}

	/** Days in the window, both ends counted. */
	public int days() {
		return (int) ChronoUnit.DAYS.between(from, to) + 1;
	}

	private static List<String> values(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<String> cleaned = raw.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(String::strip)
				.distinct()
				.toList();
		if (cleaned.size() > MAX_VALUES) {
			throw ApiException.badRequest("error.time.report.tooManyValues", MAX_VALUES);
		}
		return cleaned;
	}

	private static ZoneId zone(String requested, ZoneId fallback) {
		if (requested != null && !requested.isBlank() && requested.length() <= 64) {
			try {
				return ZoneId.of(requested.strip());
			}
			catch (DateTimeException ignored) {
				// Falls back: a zone the server does not know is no reason to refuse a report.
			}
		}
		return fallback;
	}
}
