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

/** Knowledge base article, organized as a tree per project, per team or privately. */
@Data
@Builder
@Document("articles")
// The private-page branch of every visibility query ("my pages without a place")
// seeks here instead of walking every private page of every person (HIN-129).
@org.springframework.data.mongodb.core.index.CompoundIndex(name = "author_place",
		def = "{'authorId': 1, 'projectId': 1, 'teamId': 1}")
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

	/**
	 * Plain text of {@link #contentDoc}, derived on every write by
	 * {@link com.ahmadre.hinata.richtext.RichTextService} — never set directly.
	 * Keeps the field name so this collection's text index is unchanged.
	 */
	@TextIndexed(weight = 2)
	private String content;

	/** The Lexical document — the source of truth for the article body. */
	private String contentDoc;

	/**
	 * Readable issue ids ({@code HIN-42}) this article links to, derived from
	 * {@link #contentDoc} on every write. Backlinks used to be found by scanning
	 * every article body for a {@code {{issue:KEY}}} token; a link is a node now,
	 * so the references are data and this index answers the question directly
	 * instead of a regex reading the whole collection.
	 */
	@Builder.Default
	@Indexed
	private List<String> referencedIssueKeys = new ArrayList<>();

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
