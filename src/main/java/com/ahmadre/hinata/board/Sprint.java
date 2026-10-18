package com.ahmadre.hinata.board;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@Document("sprints")
public class Sprint {

	@Id
	private String id;

	@Indexed
	private String boardId;

	@TextIndexed(weight = 10)
	private String name;

	@TextIndexed(weight = 3)
	private String goal;

	private LocalDate startDate;

	private LocalDate endDate;

	/** Story-point capacity the team commits to for this sprint. */
	private Integer capacityPoints;

	@Builder.Default
	private boolean archived = false;

	@CreatedDate
	private Instant createdAt;

	/**
	 * What the sprint held when it was completed, per project: the points committed and the points
	 * done. Completing a sprint moves its unfinished issues out, so afterwards its issues alone say
	 * only what was done; velocity needs both. Per project, so a reader sums only the projects they
	 * see. Null for a running sprint and for one completed before this was kept.
	 *
	 * <p>Stored, never sent: a sprint goes out as it is to every reader of its board, and these are
	 * keyed by project, the hidden ones included. The report sums the visible ones instead.
	 */
	@com.fasterxml.jackson.annotation.JsonIgnore
	private java.util.Map<String, ClosingPoints> closingPoints;

	/** Points committed and done in one project when the sprint closed. Boxed, as stored documents may lack them. */
	public record ClosingPoints(Integer committed, Integer completed) {

		public int committedOrZero() {
			return committed == null ? 0 : committed;
		}

		public int completedOrZero() {
			return completed == null ? 0 : completed;
		}
	}
}
