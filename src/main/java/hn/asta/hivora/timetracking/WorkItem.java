package hn.asta.hivora.timetracking;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/** A single tracked unit of work on an issue, YouTrack-style. */
@Data
@Builder
@Document("work_items")
@CompoundIndex(name = "user_date", def = "{'userId': 1, 'date': 1}")
public class WorkItem {

	@Id
	private String id;

	@Indexed
	private String issueId;

	/** Denormalized for fast timesheet aggregation. */
	@Indexed
	private String projectId;

	@Indexed
	private String userId;

	private LocalDate date;

	private int durationMinutes;

	/** Activity type, e.g. Development, Testing, Documentation, Meeting. */
	@Builder.Default
	private String activityType = "Development";

	private String description;

	@CreatedDate
	private Instant createdAt;
}
