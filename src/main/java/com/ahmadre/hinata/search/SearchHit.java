package com.ahmadre.hinata.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
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

	/** ISSUES | PROJECTS | PEOPLE | BOARDS | DOCS. */
	private String category;

	/** Entity id (used as the row key). */
	private String id;

	/** App route to navigate to on activation, e.g. {@code /issues/<id>}. */
	private String route;

	private String title;

	/** Pre-resolved subtitle for people (job title) and boards (descriptor). */
	private String subtitle;

	/** True for archived (soft-deleted) issues / archived projects, so the
	 * palette can badge them; omitted (null) for active entities. */
	private Boolean archived;

	// ---- issues ----
	private String readableId;
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
}
