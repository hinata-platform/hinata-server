package com.ahmadre.hinata.timetracking;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A sprint's time budget (HIN-156).
 *
 * <p>Its own collection rather than a field on {@code Sprint}, for the reason
 * {@link ProjectTimeSettings} is one: a sprint is read on every board, and switching the extended
 * module off must leave nothing behind in the core collections.
 */
@Data
@Builder(toBuilder = true)
@Document("sprint_time_settings")
public class SprintTimeSettings {

	@Id
	private String id;

	@Indexed(unique = true)
	private String sprintId;

	/** The time the sprint may take, recorded on its issues within its dates; null for none. */
	private Integer budgetMinutes;

	private Instant updatedAt;
	private String updatedBy;
}
