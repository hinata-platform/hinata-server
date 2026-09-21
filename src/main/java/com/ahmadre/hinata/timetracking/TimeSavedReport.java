package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A report somebody named and kept (HIN-93): its filters, how it is grouped and drawn, and
 * optionally a link to share it and a schedule to mail it.
 *
 * <p>It stores the question, never an answer. Whoever opens it — the owner, somebody the link
 * reached, a recipient of the mail — runs it in their own scope, so a shared report can never show
 * more than its reader may see anyway. The share token is kept only as a hash: a leaked database
 * does not hand out working links, and the owner can take a link back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("saved_reports")
// A person's own reports, most recently changed first, _id as the tiebreaker for stable pages.
@CompoundIndex(name = "owner_updated", def = "{'ownerId': 1, 'updatedAt': -1, '_id': -1}")
// Opening a shared link is a lookup on the hash; sparse, since most reports are never shared.
@CompoundIndex(name = "share_hash", def = "{'shareTokenHash': 1}", unique = true, sparse = true)
// The mail job's walk each hour over the reports that have a schedule. Partial, so the index
// holds only those; each schedule's hour is on its own clock, so the job decides per report.
@CompoundIndex(name = "scheduled", def = "{'schedule.cadence': 1, '_id': 1}",
		partialFilter = "{'schedule': {'$exists': true}}")
// Taking a deleted person off every recipient list.
@CompoundIndex(name = "schedule_recipients", def = "{'schedule.recipients': 1}",
		partialFilter = "{'schedule': {'$exists': true}}")
public class TimeSavedReport {

	/** Longest name, in characters. */
	public static final int NAME_MAX = 100;

	/** Most people one schedule mails. */
	public static final int RECIPIENTS_MAX = 50;

	/** Most reports one person keeps. */
	public static final int PER_OWNER_MAX = 200;

	@Id
	private String id;

	private String ownerId;

	private String name;

	private Config config;

	/** SHA-256 of the share token, hex; null while the report is not shared. */
	private String shareTokenHash;

	private Instant sharedAt;

	private Schedule schedule;

	private Instant createdAt;

	private Instant updatedAt;

	/** The window a report covers, relative to the day it is opened unless it is {@code CUSTOM}. */
	public enum Range {
		THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, LAST_30_DAYS, THIS_YEAR, CUSTOM
	}

	public enum Chart {
		BAR, PIE, LINE
	}

	public enum Cadence {
		WEEKLY, MONTHLY
	}

	/** The question a report asks: the filters of {@link TimeReportQuery} and how it is shown. */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Config {
		private Range range;
		private LocalDate from;
		private LocalDate to;
		@Builder.Default
		private List<String> projectIds = new ArrayList<>();
		@Builder.Default
		private List<String> userIds = new ArrayList<>();
		@Builder.Default
		private List<String> teamIds = new ArrayList<>();
		@Builder.Default
		private List<String> tags = new ArrayList<>();
		private Boolean billable;
		@Builder.Default
		private List<String> activities = new ArrayList<>();
		private String q;
		private Set<TimeReportFilter.Approval> approval;
		private TimePolicy.Rounding rounding;
		private Integer roundingIncrement;
		private TimeReportService.GroupBy groupBy;
		private Chart chart;
	}

	/**
	 * When the report is mailed: weekly on [dayOfWeek] or monthly on the first, from [hour] on the
	 * clock of [zone] (the owner's when the schedule was set), covering the period that just ended.
	 * A run the server missed is caught up within the same period, never repeated.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Schedule {
		private Cadence cadence;
		private DayOfWeek dayOfWeek;
		private Integer hour;
		private String zone;
		/** When the schedule was set: nothing due before it is sent. */
		private Instant since;
		@Builder.Default
		private List<String> recipients = new ArrayList<>();
	}
}
