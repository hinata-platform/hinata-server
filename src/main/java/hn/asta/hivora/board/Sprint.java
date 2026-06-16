package hn.asta.hivora.board;

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
}
