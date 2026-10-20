package com.ahmadre.hinata.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One global-search result, ready for the palette to render. Carries raw
 * fields (state, type, updatedAt, …) rather than presentation strings so the
 * client composes/localises labels itself. Null fields are omitted from JSON.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchHit {

	/** One of {@link SearchCategory}. */
	private String category;

	/**
	 * What the hit is within its category, where a category holds more than one
	 * kind (HIN-165): {@code BOARD}/{@code SPRINT}, {@code ARTICLE}/{@code SPACE},
	 * {@code EVENT}/{@code SHIFT}, {@code ENTRY}/{@code TIMESHEET},
	 * {@code REQUEST}/{@code TYPE}, {@code BUDGET}/{@code RATE}. Omitted where a
	 * category has one kind only.
	 */
	private String kind;

	/** Entity id (used as the row key). */
	private String id;

	/** App route to navigate to on activation, e.g. {@code /issues/<id>}. */
	private String route;

	private String title;

	/**
	 * The second line of a hit; what it holds depends on the category: a person's job title, a
	 * board's descriptor or a sprint's goal, the issue's title for a comment or an attachment, a
	 * team's or a space's description, the event's title for a shift, the note of an absence
	 * request. Omitted where there is none.
	 */
	private String subtitle;

	/** True for archived (soft-deleted) issues / archived projects, so the
	 * palette can badge them; omitted (null) for active entities. */
	private Boolean archived;

	// ---- issues ----
	private String readableId;
	/** Issues: the issue type (e.g. {@code TASK}). */
	private String type;
	private String state;
	private String assigneeName;
	private String assigneeAvatarUrl;

	// ---- people ----
	private String avatarUrl;

	// ---- projects ----
	private String projectKey;
	private String projectColor;
	private Integer openCount;
	private Integer doneCount;
	private List<String> memberNames;

	// ---- docs ----
	private String space;
	private Instant updatedAt;

	// ---- HIN-165 ----

	/** People: the account name; teams: the team key. */
	private String username;
	private String key;

	/** The project a hit belongs to, for the hits that have one and that the caller sees. */
	private String projectId;
	private String projectName;

	/** Comments and attachments: the issue they sit on. */
	private String issueId;

	/** Comments: who wrote it; also the moment for comments. */
	private String authorName;
	private Instant createdAt;

	/** Attachments: the file's media type and size in bytes. */
	private String contentType;
	private Long size;

	/** Shift events and shifts: when they run, and the event's place. */
	private Instant startsAt;
	private Instant endsAt;
	private String location;
	private Boolean cancelled;

	/** Time entries, timesheets, absence requests: the days (ISO dates). */
	private LocalDate from;
	private LocalDate to;
	private Integer minutes;

	/** Timesheets and absence requests: their status as stored (e.g. {@code APPROVED}). */
	private String status;

	/** Absence types, spaces and teams: their icon and hue; rates: amount in cents and currency. */
	private String icon;
	private Integer hue;
	private Long amountCents;
	private String currency;

	/**
	 * Rates: what the rate applies to, as stored ({@code PROJECT}, {@code PROJECT_MEMBER},
	 * {@code ISSUE}, {@code USER}, {@code TEAM} or {@code DEFAULT}).
	 */
	private String rateLevel;

	/** Rates: {@code BILLABLE} (revenue) or {@code COST}. */
	private String rateKind;
}
