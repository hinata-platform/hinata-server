package com.ahmadre.hinata.timetracking;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A single tracked unit of work, YouTrack-style: a calendar day, a duration and
 * an activity, usually pinned to an issue.
 *
 * <p>{@code date} is a calendar day and stays one: it is stored as UTC midnight
 * and no time zone is ever applied to it. The zone only matters when a day is
 * <em>derived</em> — from a timer's {@link #startedAt} or from "today" for the
 * date rules — and that happens in the user's zone before the day is stored.
 *
 * <p>{@code issueId} and {@code projectId} are both nullable by design: deleting
 * an issue detaches its entries rather than deleting them (the hours were
 * worked, the project file keeps them), and later stages log time without an
 * issue at all. Every reader has to cope with either being absent.
 */
@Data
// toBuilder so a write can hold on to what the entry looked like before it was
// touched: TimeTrackingService#assertWritable is asked about both states, and
// from stage 6 a lock date or an approval applies to the day an entry is moving
// off just as much as to the day it is moving to.
@Builder(toBuilder = true)
@Document("work_items")
// The timesheet and the dashboard trackers ask for one user's days in a range:
// equality on userId, then the date range — ESR — so the scan is bounded to that
// user's rows and Mongo walks the range straight off the index.
@CompoundIndex(name = "user_date", def = "{'userId': 1, 'date': 1}")
// Reports and the project-scoped timesheet ask the same question per project.
@CompoundIndex(name = "project_date", def = "{'projectId': 1, 'date': 1}")
// The per-issue list, newest first, is what every issue open pays for. Equality
// on issueId, then the sort keys in the order the list sorts by (date, then _id
// as the tiebreaker) so the capped list and every page come back as one bounded
// IXSCAN with no blocking sort — the same tiebreaker rule Issue's list indexes
// follow. Counting an issue's entries and detaching them use the prefix.
@CompoundIndex(name = "issue_date", def = "{'issueId': 1, 'date': -1, '_id': -1}")
// The self-scoped timesheet narrowed to one project: two equalities (userId,
// projectId), then the date range. user_date alone would have to filter the
// project out of every day the user ever logged.
@CompoundIndex(name = "user_project_date", def = "{'userId': 1, 'projectId': 1, 'date': 1}")
// The admin timesheet and the cross-project time report ask for a window and
// nothing else, so there is no equality to lead with and `date` has to. It is
// the second key in all four indexes above, and a second key is not a prefix —
// without this one that query has nothing to use and reads the collection.
// userId and projectId follow because they are what the rows are grouped by.
@CompoundIndex(name = "date_user_project", def = "{'date': 1, 'userId': 1, 'projectId': 1}")
public class WorkItem {

	/**
	 * Where an entry came from. Absent on documents written before 2.0 — read as
	 * {@link #APP}.
	 *
	 * <p>Four of these are written today: {@code APP}, {@code MCP},
	 * {@code SMART_COMMIT} and the {@code LEGACY} remainders the schema
	 * migration settles. The rest name the writers the later stages of the epic
	 * bring — a timer, a calendar import, a CSV import, a shared entry — and are
	 * listed here so the stored value never has to change meaning later.
	 */
	public enum Source {
		/** Logged by hand in the app (or through the REST API). */
		APP,
		/** Stopped timer. */
		TIMER,
		/** The {@code log_work} MCP tool. */
		MCP,
		/** A {@code #time} smart commit, attributed to the commit author. */
		SMART_COMMIT,
		/**
		 * Time an issue carried in {@code spentMinutes} without a matching entry
		 * (smart commits before 2.0). Never attributed to a person: {@code userId}
		 * is null.
		 */
		LEGACY,
		/** Imported from a calendar. */
		CALENDAR,
		/** Imported from a CSV file. */
		CSV,
		/** Copied from another user's shared entry (see {@link #sharedFromId}). */
		SHARED
	}

	@Id
	private String id;

	/** The issue the time was booked on; null once that issue has been deleted. */
	private String issueId;

	/** Denormalized for fast timesheet aggregation; follows the issue across a move. */
	private String projectId;

	/** Who did the work; null only for {@link Source#LEGACY} entries. */
	private String userId;

	/** The calendar day the work belongs to — a date, never a point in time. */
	private LocalDate date;

	private int durationMinutes;

	/** Activity type, e.g. Development, Testing, Documentation, Meeting. */
	@Builder.Default
	private String activityType = "Development";

	private String description;

	@CreatedDate
	private Instant createdAt;

	/** When the work started, if it was timed rather than typed. */
	private Instant startedAt;

	/** When the work ended; with {@link #startedAt} it defines the duration. */
	private Instant endedAt;

	/** Whether the time is billable. Written on every save, defaults to false. */
	@Builder.Default
	private boolean billable = false;

	@Builder.Default
	private List<String> tags = new ArrayList<>();

	@Builder.Default
	private Source source = Source.APP;

	/** When the entry was last edited, and by whom; null until it is. */
	private Instant updatedAt;

	private String updatedBy;

	/** The entry this one was copied from, for {@link Source#SHARED}. */
	private String sharedFromId;

	/**
	 * Documents written before 2.0 carry no {@code source}; they were logged in
	 * the app or through the API, which is what {@link Source#APP} means.
	 * Spring Data maps through the fields, so this normalization is read-only.
	 */
	public Source getSource() {
		return source == null ? Source.APP : source;
	}

	/** Never null: older documents have no {@code tags} field at all. */
	public List<String> getTags() {
		return tags == null ? List.of() : tags;
	}
}
