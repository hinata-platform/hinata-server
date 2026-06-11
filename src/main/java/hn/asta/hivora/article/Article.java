package hn.asta.hivora.article;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Knowledge base article, organized as a tree per project or globally. */
@Data
@Builder
@Document("articles")
public class Article {

	@Id
	private String id;

	/** Null for organization-wide articles. */
	@Indexed
	private String projectId;

	/** Parent article id for hierarchical organization. */
	@Indexed
	private String parentId;

	private String title;

	/** Markdown. */
	private String content;

	@Builder.Default
	private List<String> tags = new ArrayList<>();

	private String authorId;

	@Builder.Default
	private int sortOrder = 0;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;
}
