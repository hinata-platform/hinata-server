package hn.asta.hivora.issue;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
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
public class IssueActivity {

	public enum Field {
		CREATED, TITLE, DESCRIPTION, STATE, ASSIGNEE, PRIORITY, TYPE, SPRINT,
		START_DATE, DUE_DATE, ESTIMATE, TAGS
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
