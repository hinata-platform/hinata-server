package com.ahmadre.hinata.article;

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

/** Knowledge base article, organized as a tree per project or globally. */
@Data
@Builder
@Document("articles")
public class Article {

	@Id
	private String id;

	/** Project the article is scoped to; only members with project access see it. */
	@Indexed
	private String projectId;

	/** Team the article belongs to (team-wide, no project). Null when project-scoped or global. */
	@Indexed
	private String teamId;

	/** Parent article id for hierarchical organization. */
	@Indexed
	private String parentId;

	/** Knowledge-base space the article lives in (e.g. "Engineering"). */
	@Indexed
	private String space;

	/** Lucide icon name (kebab-case) for the article glyph. */
	private String icon;

	@TextIndexed(weight = 10)
	private String title;

	/** Markdown. */
	@TextIndexed(weight = 2)
	private String content;

	@Builder.Default
	@TextIndexed(weight = 5)
	private List<String> tags = new ArrayList<>();

	private String authorId;

	@Builder.Default
	private int sortOrder = 0;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;
}
