package hn.asta.hivora.board;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
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

	private String name;

	private String goal;

	private LocalDate startDate;

	private LocalDate endDate;

	@Builder.Default
	private boolean archived = false;

	@CreatedDate
	private Instant createdAt;
}
