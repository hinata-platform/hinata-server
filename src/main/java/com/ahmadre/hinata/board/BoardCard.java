package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An issue the way a board shows it: as a card on the wall, a row in the sprint planning, a bar
 * on the timeline.
 *
 * <p>Deliberately not {@link Issue}. A board lists hundreds of issues at a time, and the
 * description, its Lexical document, the attachments, the watchers and the mail fields make up
 * most of an issue's weight while no board draws any of them. The query reads exactly
 * {@link #FIELDS}, so that weight never leaves the database. The names match {@link Issue}, so a
 * client reads a card with the parser it already has for issues.
 *
 * @param epicId           the epic the issue rolls up to: its parent, or for a sub-task its
 *                         grandparent; null when there is none the viewer may see
 * @param subtaskCount     the issue's direct children
 * @param subtaskDoneCount of those, the ones in a resolved state or with a resolution date
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoardCard(String id, String projectId, String readableId, String title, String state,
		Issue.Type type, Issue.Priority priority, String assigneeId, List<String> assigneeIds,
		String reporterId, List<String> tags, String parentId, String epicId, String sprintId,
		LocalDate startDate, LocalDate dueDate, Integer estimateMinutes, Integer storyPoints,
		int spentMinutes, double rank, Instant resolvedAt, List<String> dependsOnIds, int subtaskCount,
		int subtaskDoneCount) {

	/** What a card reads from an issue document, besides its id. */
	static final String[] FIELDS = { "projectId", "readableId", "title", "state", "type", "priority",
			"assigneeId", "assigneeIds", "reporterId", "tags", "parentId", "sprintId", "startDate",
			"dueDate", "estimateMinutes", "storyPoints", "spentMinutes", "rank", "resolvedAt",
			"dependsOnIds" };

	static BoardCard of(Issue issue, String epicId, int subtaskCount, int subtaskDoneCount) {
		return new BoardCard(issue.getId(), issue.getProjectId(), issue.getReadableId(), issue.getTitle(),
				issue.getState(), issue.getType(), issue.getPriority(), issue.getAssigneeId(),
				orEmpty(issue.getAssigneeIds()), issue.getReporterId(), orEmpty(issue.getTags()),
				issue.getParentId(), epicId, issue.getSprintId(), issue.getStartDate(), issue.getDueDate(),
				issue.getEstimateMinutes(), issue.getStoryPoints(), issue.getSpentMinutes(), issue.getRank(),
				issue.getResolvedAt(), orEmpty(issue.getDependsOnIds()), subtaskCount, subtaskDoneCount);
	}

	/** A projected document lacks a list it never had, where a saved issue holds an empty one. */
	private static List<String> orEmpty(List<String> values) {
		return values == null ? List.of() : values;
	}
}
