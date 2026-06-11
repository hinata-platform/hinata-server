package hn.asta.hivora.project;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@Document("projects")
public class Project {

	@Id
	private String id;

	/** Short uppercase key used for issue numbering, e.g. "ASTA" -> ASTA-42. */
	@Indexed(unique = true)
	private String key;

	private String name;

	private String description;

	/** User id of the project lead. */
	private String leadId;

	@Builder.Default
	private List<String> memberIds = new ArrayList<>();

	/** Ordered workflow states; doubles as default agile board columns. */
	@Builder.Default
	private List<String> workflowStates = new ArrayList<>(DEFAULT_STATES);

	/** States counted as "done" for progress and reports. */
	@Builder.Default
	private List<String> resolvedStates = new ArrayList<>(DEFAULT_RESOLVED);

	/** Accent color (hex) used by the app, following the pastel design system. */
	@Builder.Default
	private String color = "#AEC6F4";

	@Builder.Default
	private boolean archived = false;

	/** Monotonic counter backing per-project issue numbers. */
	@Builder.Default
	private long issueCounter = 0;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public static final List<String> DEFAULT_STATES =
			List.of("Backlog", "Open", "In Progress", "In Review", "Done");
	public static final List<String> DEFAULT_RESOLVED = List.of("Done");
}
