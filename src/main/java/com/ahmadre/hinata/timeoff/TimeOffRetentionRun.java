package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One night's absence retention (HIN-119). The id is the day; inserting it claims the night, so two
 * instances never sweep at once — the pattern {@code timetracking.TimeRetentionRun} set.
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_retention_runs")
public class TimeOffRetentionRun {

	@Id
	private String id;

	private Instant startedAt;

	private Instant finishedAt;

	/** Sick absences and sick reports that now read "away (other)". */
	private long sickCoarsened;

	/** Refused, withdrawn and cancelled requests removed. */
	private long requestsRemoved;

	/** Journal rows removed under an explicit journal retention. */
	private long ledgerRemoved;
}
