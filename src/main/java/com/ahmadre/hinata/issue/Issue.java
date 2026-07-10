package com.ahmadre.hinata.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@Document("issues")
@CompoundIndex(name = "project_number", def = "{'projectId': 1, 'numberInProject': 1}", unique = true)
public class Issue {

	public enum Type {
		TASK, BUG, FEATURE, STORY, EPIC, SUBTASK;

		/** The top of the hierarchy: holds standard issues, never has a parent. */
		public boolean isEpic() {
			return this == EPIC;
		}

		/** The leaf level: lives under a standard issue, never holds children. */
		public boolean isSubtask() {
			return this == SUBTASK;
		}

		/** Story / Task / Bug / Feature — may sit under an epic and hold sub-tasks. */
		public boolean isStandard() {
			return !isEpic() && !isSubtask();
		}
	}

	public enum Priority { SHOWSTOPPER, CRITICAL, MAJOR, NORMAL, MINOR }

	@Id
	private String id;

	@Indexed
	private String projectId;

	/** Sequential number inside the project; readable id = "<KEY>-<number>". */
	private long numberInProject;

	/** Denormalized readable id, e.g. ASTA-42. */
	@Indexed
	private String readableId;

	@TextIndexed(weight = 10)
	private String title;

	/** Markdown. */
	@TextIndexed(weight = 2)
	private String description;

	@Builder.Default
	private Type type = Type.TASK;

	@Builder.Default
	private Priority priority = Priority.NORMAL;

	/** One of the project's workflowStates. */
	@Indexed
	private String state;

	/**
	 * Primary assignee — always the first entry of {@link #assigneeIds}, kept in
	 * sync by the custom setters below. The many single-assignee read sites (board
	 * swimlanes, gantt, reports, sprint workload) read this field unchanged.
	 */
	private String assigneeId;

	/**
	 * All assignees. The platform runs in single- or multi-assignee mode (admin
	 * feature flag {@code multi_assignee}); the model always stores a list.
	 */
	@Builder.Default
	private List<String> assigneeIds = new ArrayList<>();

	private String reporterId;

	/** Set when the issue was created from an inbound e-mail. */
	private String reporterEmail;

	@Builder.Default
	@TextIndexed(weight = 5)
	private List<String> tags = new ArrayList<>();

	@Builder.Default
	private List<String> watcherIds = new ArrayList<>();

	/** Parent epic / parent task for subtasks. */
	private String parentId;

	/** Issue ids this issue depends on – drives the Gantt chart. */
	@Builder.Default
	private List<String> dependsOnIds = new ArrayList<>();

	@Indexed
	private String sprintId;

	/** Planning fields for Gantt / scheduling. */
	private LocalDate startDate;
	private LocalDate dueDate;

	/**
	 * The {@link #dueDate} value the "due soon" reminder was last sent for. The
	 * daily job only reminds when this differs from the current due date, so it
	 * never re-notifies — yet automatically re-arms if the due date is changed.
	 */
	private LocalDate dueReminderFor;

	private Integer estimateMinutes;

	/** Scrum effort estimate in story points (Fibonacci); null = unestimated. */
	private Integer storyPoints;

	/** Denormalized sum of work items, kept in sync by TimeTrackingService. */
	@Builder.Default
	private int spentMinutes = 0;

	@Builder.Default
	private List<Attachment> attachments = new ArrayList<>();

	/** Manual ordering inside board columns and backlog. */
	@Builder.Default
	private double rank = 0;

	private Instant resolvedAt;

	/**
	 * Soft-delete: an archived issue is hidden from every default listing
	 * (search, board, backlog, sprints) but keeps all its data and can be
	 * restored. Any project member may archive; hard delete is role-gated.
	 *
	 * <p>Stored as a wrapper because pre-migration documents lack the field —
	 * a primitive would make Spring Data's constructor mapping reject them
	 * (null → boolean). The manual accessors keep the boolean API.
	 */
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	@Indexed
	private Boolean archived;

	private Instant archivedAt;

	@JsonProperty("archived")
	public boolean isArchived() {
		return Boolean.TRUE.equals(archived);
	}

	public void setArchived(boolean archived) {
		this.archived = archived;
	}

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/**
	 * Sets the full assignee list (multi-assignee mode), de-duplicated and blank-
	 * stripped, and keeps {@link #assigneeId} pointing at the primary (first).
	 */
	public void setAssigneeIds(List<String> ids) {
		List<String> clean = new ArrayList<>();
		if (ids != null) {
			for (String each : ids) {
				if (each != null && !each.isBlank() && !clean.contains(each)) clean.add(each);
			}
		}
		this.assigneeIds = clean;
		this.assigneeId = clean.isEmpty() ? null : clean.get(0);
	}

	/**
	 * Sets the single (primary) assignee, replacing any others — used in
	 * single-assignee mode and by the legacy {@code assigneeId} API field.
	 */
	public void setAssigneeId(String id) {
		String primary = (id == null || id.isBlank()) ? null : id;
		this.assigneeId = primary;
		this.assigneeIds = primary == null ? new ArrayList<>() : new ArrayList<>(List.of(primary));
	}

	@Data
	@Builder
	public static class Attachment {
		private String id;
		private String fileName;
		private String contentType;
		private long size;
		/** Object key in the S3 bucket; download only via presigned URLs. */
		private String objectKey;
		private String uploaderId;
		private Instant uploadedAt;
	}
}
