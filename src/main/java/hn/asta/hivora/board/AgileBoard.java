package hn.asta.hivora.board;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@Document("agile_boards")
public class AgileBoard {

	@Id
	private String id;

	private String name;

	/** Boards can span multiple projects, like YouTrack agile boards. */
	@Builder.Default
	private List<String> projectIds = new ArrayList<>();

	/** Each column maps to one or more workflow states. */
	@Builder.Default
	private List<Column> columns = new ArrayList<>();

	/** Currently active sprint shown by default. */
	private String activeSprintId;

	private String ownerId;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

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
