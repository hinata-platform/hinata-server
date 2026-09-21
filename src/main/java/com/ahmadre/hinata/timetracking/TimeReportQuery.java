package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A report as a request states it, before anything is checked: the query parameters of every
 * report and export route. {@link TimeReportService#filter} turns it into a
 * {@link TimeReportFilter}, the only shape a report reads.
 *
 * @param q        a word the description must contain
 * @param rounding the rounding mode; the policy's when absent
 * @param tz       the zone start and end times are written in; the reader's when absent
 */
public record TimeReportQuery(
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		List<String> projectIds, List<String> userIds, List<String> teamIds, List<String> tags, Boolean billable,
		List<String> activities, String q, Set<TimeReportFilter.Approval> approval, TimePolicy.Rounding rounding,
		Integer roundingIncrement, String tz) {
}
