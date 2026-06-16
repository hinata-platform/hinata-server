package hn.asta.hivora.issue;

import lombok.Builder;
import lombok.Data;
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

	public enum Type { TASK, BUG, FEATURE, EPIC }

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

	private String assigneeId;

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

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

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
