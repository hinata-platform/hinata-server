package com.ahmadre.hinata.richtext;

import java.util.List;

/**
 * Rich-text content: the Lexical document that is the source of truth, and the
 * plain text derived from it.
 *
 * <p>The two always travel together and are only ever produced by
 * {@link RichTextService}, so {@link #text()} is by construction the plain-text
 * projection of {@link #doc()} rather than something a caller supplied. Entities
 * persist them as two fields, and the plain text deliberately keeps the field
 * name the markdown used to occupy ({@code Issue.description},
 * {@code Article.content}) for two reasons:
 *
 * <ul>
 *   <li>The Mongo text index keeps its exact key spec. Moving an indexed field
 *       would make Spring Data try to create a second text index on a collection
 *       that already has one, which Mongo refuses — the server would stop booting
 *       against any database that already holds data.</li>
 *   <li>Every plain-text consumer — full-text search, notification teasers, the
 *       GDPR export, board cards — keeps reading the same field and now gets
 *       something cleaner than the markdown ever was: no syntax characters and no
 *       {@code {{user:<objectid>}}} tokens, just words.</li>
 * </ul>
 *
 * @param doc       Lexical JSON, or {@code null} for "no content"
 * @param text      plain text derived from {@code doc}; never {@code null}
 * @param issueKeys readable issue ids the document links to, derived from
 *                  {@code doc}; never {@code null}
 */
public record RichText(String doc, String text, List<String> issueKeys) {

	/** No content: no document, empty text, no references. */
	public static final RichText EMPTY = new RichText(null, "", List.of());

	public RichText {
		text = text == null ? "" : text;
		issueKeys = issueKeys == null ? List.of() : List.copyOf(issueKeys);
	}

	/** Whether this carries no readable content. */
	public boolean isBlank() {
		return text.isBlank() && (doc == null || doc.isBlank());
	}
}
