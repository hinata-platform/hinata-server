package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An issue a board names without showing it as a card: the epic a lane is headed by or the
 * filter offers, and the parent a sub-task sits under. Only what a lane header draws.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoardRef(String id, String projectId, String readableId, String title, String state,
		Issue.Type type, String parentId) {

	/** What a reference reads from an issue document, besides its id. */
	static final String[] FIELDS = { "projectId", "readableId", "title", "state", "type", "parentId" };

	static BoardRef of(Issue issue) {
		return new BoardRef(issue.getId(), issue.getProjectId(), issue.getReadableId(), issue.getTitle(),
				issue.getState(), issue.getType(), issue.getParentId());
	}
}
