package com.ahmadre.hinata.issue;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One recorded change to an issue — the "Verlauf" / change history.
 * Created on issue creation ({@link Field#CREATED}) and once per changed
 * field on every update.
 */
@Data
@Builder
@Document("issue_activities")
// The activity feed filters by issueId and sorts by createdAt desc on every
// issue open; this compound serves filter+sort as an IXSCAN.
@CompoundIndex(name = "issue_created", def = "{'issueId': 1, 'createdAt': -1}")
// A completed sprint's carried-over work, read for the velocity of sprints closed before their
// points were kept (SprintService.carriedOut). Sprint moves only, so the index stays small.
@CompoundIndex(name = "sprint_moves_from", def = "{'fromValue': 1, 'createdAt': 1}",
		partialFilter = "{'field': 'SPRINT'}")
// The other half of a sprint's scope log (SprintService.scopeChanges asks for moves to or from it).
@CompoundIndex(name = "sprint_moves_to", def = "{'toValue': 1, 'createdAt': 1}",
		partialFilter = "{'field': 'SPRINT'}")
public class IssueActivity {

	public enum Field {
		CREATED, TITLE, DESCRIPTION, STATE, ASSIGNEE, PRIORITY, TYPE, SPRINT,
		START_DATE, DUE_DATE, ESTIMATE, STORY_POINTS, TAGS, PARENT,
		/** Moved to another project; from/to hold the readable ids, e.g. VOR-42 → ERSTI-7. */
		PROJECT,
		/** Shared with somebody outside the project; {@code toValue} holds their user id. */
		PARTICIPANT_ADDED,
		/** Taken off the issue again; {@code fromValue} holds their user id. */
		PARTICIPANT_REMOVED
	}

	@Id
	private String id;

	@Indexed
	private String issueId;

	/** User who performed the change; null for system-driven changes. */
	private String actorId;

	private Field field;

	/**
	 * Previous / new value as display strings (state code, enum name, user id,
	 * sprint id, ISO date, …). Both null where a value carries no useful diff
	 * (e.g. a description edit), or {@code fromValue} null on creation.
	 */
	private String fromValue;
	private String toValue;

	@CreatedDate
	private Instant createdAt;
}
