package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One CSV import between its preview and its commit (HIN-93): the rows that passed every check,
 * the ones that did not and why, and where the commit stands.
 *
 * <p>The rows are kept checked and resolved, so the commit writes what the preview showed — and
 * checks each row once more, because a lock date or an approval may have moved in between.
 * Nothing here is ever an entry: an entry exists once {@link Status#COMMITTED}, marked with this
 * import's id so a commit that fails half way is taken back whole.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("time_imports")
// Whose imports, newest first: the one pending preview a person replaces with the next.
@CompoundIndex(name = "owner_created", def = "{'ownerId': 1, 'createdAt': -1}")
// The sweep's question: which commits were started and never finished.
@CompoundIndex(name = "status_started", def = "{'status': 1, 'commitStartedAt': 1}")
public class TimeImport {

	public enum Status {
		PENDING, COMMITTING, COMMITTED, FAILED
	}

	@Id
	private String id;

	/** Who uploaded the file and may commit it. */
	private String ownerId;

	/** Whose entries the rows become: the uploader, or anybody for an administrator. */
	private String targetUserId;

	private Status status;

	private String fileName;

	private int totalRows;

	private Instant createdAt;

	private Instant commitStartedAt;

	private Instant finishedAt;

	private int inserted;

	/** Why a commit was taken back, as a message key. */
	private String failure;

	@Builder.Default
	private List<Row> rows = new ArrayList<>();

	@Builder.Default
	private List<RowError> errors = new ArrayList<>();

	/**
	 * When Mongo removes the document: a day after a preview nobody committed, a week after it
	 * was committed or failed. Long enough to answer "did my import go through", short enough
	 * that a file's content does not linger.
	 */
	@Indexed(expireAfter = "0s")
	private Instant expiresAt;

	/** A row that passed: an entry for [userId], checked and placed. */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Row {
		private int line;
		private String userId;
		private String projectId;
		private String issueId;
		private LocalDate date;
		private int minutes;
		private Instant startedAt;
		private Instant endedAt;
		private String activityType;
		private String description;
		@Builder.Default
		private List<String> tags = new ArrayList<>();
		private boolean billable;
	}

	/** A row that did not, with the reason in the uploader's language. */
	public record RowError(int line, String message) {
	}
}
