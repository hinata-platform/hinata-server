package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One night's claim on the retention sweep, and what it did.
 *
 * <p>The claim is the insert. {@code _id} is the day, so the second instance to
 * try the same night gets a duplicate-key error and walks away — a sweep that
 * deletes records must not run twice in parallel, and a lock that could expire
 * mid-run would be a worse answer than a key that cannot be taken twice. The
 * counters are written back when the run ends, beside the audit record that states
 * them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("time_retention_runs")
public class TimeRetentionRun {

	/** The day of the run ({@code 2026-09-13}), or a named one-off marker. */
	@Id
	private String id;

	private Instant startedAt;

	private Instant finishedAt;

	private long descriptionsCleared;

	/** Notes emptied on the timesheets of deleted accounts. */
	private long approvalNotesCleared;

	private long entriesDeleted;

	/** Entries old enough to go but inside a submitted or approved period. */
	private long entriesKept;

	/** False when the run stopped at its time budget; the next night continues. */
	private boolean complete;

	/** On the {@code entry-cursor} document only: the day the entry sweep stopped on. */
	private LocalDate cursorDay;

	/** On the {@code entry-cursor} document only: the last entry it looked at that day. */
	private String cursorId;
}
