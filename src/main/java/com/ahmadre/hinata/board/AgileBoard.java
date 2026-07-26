package com.ahmadre.hinata.board;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@Document("agile_boards")
public class AgileBoard {

	/**
	 * Working mode of the board:
	 * <ul>
	 *   <li>{@link #KANBAN} – continuous flow, no fixed timeboxes (default).</li>
	 *   <li>{@link #SCRUM} – sprint planning in fixed iterations; unlocks the
	 *       sprint planning / active-sprint / insights surfaces.</li>
	 * </ul>
	 */
	public enum Type { KANBAN, SCRUM }

	@Id
	private String id;

	@TextIndexed(weight = 10)
	private String name;

	@Builder.Default
	private Type type = Type.KANBAN;

	/** Boards can span multiple projects, like YouTrack agile boards. */
	// Multikey index: findByProjectIdsContains runs on every issue open (sprint
	// picker) and every board list.
	@Indexed
	@Builder.Default
	private List<String> projectIds = new ArrayList<>();

	/** Each column maps to one or more workflow states. */
	@Builder.Default
	private List<Column> columns = new ArrayList<>();

	/**
	 * Whether a manager arranged {@link #columns} by hand. While false the board
	 * derives its columns from the spanned workflows on every view, so renames and
	 * new states show up by themselves; once true the stored layout is the truth
	 * and the automatic merge only fills in states that have no column yet.
	 *
	 * <p>Wrapper type on purpose: boards written before this existed have no such
	 * field, and a primitive would make Spring Data fail to map them.
	 */
	private Boolean columnsCustomized;

	/** Currently active sprint shown by default. */
	private String activeSprintId;

	private String ownerId;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	/** Whether {@link #columns} is a hand-made layout rather than a derived one. */
	public boolean hasCustomColumns() {
		return Boolean.TRUE.equals(columnsCustomized);
	}

	@Data
	@Builder
	public static class Column {
		private String name;
		@Builder.Default
		private List<String> states = new ArrayList<>();
		/** Work-in-progress limit; null = unlimited. */
		private Integer wipLimit;
	}
}
