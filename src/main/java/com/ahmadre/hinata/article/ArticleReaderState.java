package com.ahmadre.hinata.article;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * What one person did with one page: marked it as a favourite, last opened it.
 * One row per person and page, so starring and reading never race into two rows.
 * Personal data: it goes into the person's export and leaves with their account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("article_reader_state")
@CompoundIndex(name = "user_article", def = "{'userId': 1, 'articleId': 1}", unique = true)
public class ArticleReaderState {

	@Id
	private String id;

	private String userId;

	/** Indexed on its own: a deleted page takes its rows along by this field alone. */
	@Indexed
	private String articleId;

	/** When the page became a favourite; {@code null} when it is none. */
	private Instant starredAt;

	/** When the person last opened the page; {@code null} once it fell off the recent list. */
	private Instant viewedAt;
}
