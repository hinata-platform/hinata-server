package com.ahmadre.hinata.article;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One saved state of a knowledge-base page: its title and document as somebody
 * left them. Numbered from 1 per page, so "version 4" means the same thing to
 * everybody reading the history. Who may read a version follows who may read the
 * page; a version has no place of its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("article_versions")
@CompoundIndex(name = "article_number", def = "{'articleId': 1, 'number': -1}", unique = true)
public class ArticleVersion {

	@Id
	private String id;

	private String articleId;

	private int number;

	private String title;

	/** The Lexical document of this state; {@code null} for an empty page. */
	private String contentDoc;

	/** Who saved this state; null for one moderation wrote. */
	private String editorId;

	/** Why the state was written, when not by an edit: {@code Von der Moderation zurückgesetzt} (HIN-169). */
	private String note;

	/** When it was saved. Set by the writer, not by auditing: a backfilled first version keeps the page's date. */
	private Instant createdAt;
}
