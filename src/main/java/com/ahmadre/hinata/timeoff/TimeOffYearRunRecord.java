package com.ahmadre.hinata.timeoff;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One night's yearly run: that it ran, and what it booked (HIN-119).
 *
 * <p>The id is the day, so inserting it is how one instance claims the night — a second one gets a
 * duplicate key and does nothing, the way {@code timetracking.TimeRetentionRun} claims its night.
 * The counts are what the admin page shows as "last run".
 */
@Data
@Builder(toBuilder = true)
@Document("time_off_year_runs")
public class TimeOffYearRunRecord {

	@Id
	private String id;

	private Instant startedAt;

	private Instant finishedAt;

	/** Monthly accruals booked. */
	private int accrued;

	/** People whose leftover was carried into the new year. */
	private int carried;

	/** Lapses booked, each with a notice behind it. */
	private int expired;

	/** People whose days would have lapsed but stayed, because nobody had told them. */
	private int held;

	/** Proposals opened for leave that could not be taken because of illness. */
	private int proposals;

	/** Whether the run stopped on an error; the next night picks up where the keys say. */
	private boolean failed;
}
