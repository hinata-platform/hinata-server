package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * Lean, JSON-serialized projections returned by MCP tools. Deliberately omits
 * internal / storage-only fields (S3 object keys, board ranks, denormalized
 * counters not useful to a client) so tool output can never leak them, and keeps
 * the payload small for the model's context.
 */
public final class McpViews {

	private McpViews() {
	}

	/** A single issue, without attachment object keys or ordering internals. */
	public record IssueView(
			String id, String readableId, String projectId, String title, String description,
			String type, String priority, String state,
			String assigneeId, List<String> assigneeIds, String reporterId,
			List<String> tags, String parentId, List<String> dependsOnIds, String sprintId,
			LocalDate startDate, LocalDate dueDate,
			Integer estimateMinutes, Integer storyPoints, int spentMinutes,
			Instant resolvedAt, Instant createdAt, Instant updatedAt) {

		public static IssueView of(Issue issue) {
			return new IssueView(
					issue.getId(), issue.getReadableId(), issue.getProjectId(),
					issue.getTitle(), issue.getDescription(),
					issue.getType() == null ? null : issue.getType().name(),
					issue.getPriority() == null ? null : issue.getPriority().name(),
					issue.getState(), issue.getAssigneeId(), issue.getAssigneeIds(),
					issue.getReporterId(), issue.getTags(), issue.getParentId(),
					issue.getDependsOnIds(), issue.getSprintId(),
					issue.getStartDate(), issue.getDueDate(),
					issue.getEstimateMinutes(), issue.getStoryPoints(), issue.getSpentMinutes(),
					issue.getResolvedAt(), issue.getCreatedAt(), issue.getUpdatedAt());
		}
	}

	/** A single issue comment. */
	public record CommentView(String id, String issueId, String authorId, String text,
			Instant createdAt, Instant updatedAt) {

		public static CommentView of(IssueComment comment) {
			return new CommentView(comment.getId(), comment.getIssueId(), comment.getAuthorId(),
					comment.getText(), comment.getCreatedAt(), comment.getUpdatedAt());
		}
	}

	/** Pagination envelope mirroring Spring Data {@link Page} in a lean shape. */
	public record PageResult<T>(List<T> items, long totalElements, int totalPages,
			int page, int size) {

		public static <E, V> PageResult<V> of(Page<E> page, Function<E, V> mapper) {
			return new PageResult<>(
					page.getContent().stream().map(mapper).toList(),
					page.getTotalElements(), page.getTotalPages(),
					page.getNumber(), page.getSize());
		}
	}
}
