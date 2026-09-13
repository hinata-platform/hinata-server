package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.richtext.RichTextService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time re-derivation of {@code articles.referencedIssueKeys} from the stored
 * Lexical document.
 *
 * <p>The backlink index was derived with a key pattern of letters only, so a link
 * to an issue of a project whose key carries digits ({@code EP26-2}) was dropped
 * when the article was written. Fixing the pattern corrects every future write,
 * but the rows already stored would keep answering "no articles reference this
 * issue" until each one happened to be edited again. This walks the articles once
 * and writes the list the current derivation produces wherever it differs.
 *
 * <p>Same safety properties as {@link MarkdownToLexicalBackfill}: idempotent (a
 * row whose list already matches is not written), non-fatal (neither a document
 * that cannot be read nor a failed batch stops the server from starting), bounded
 * memory (rows stream in batches), and finished once — a completion marker in
 * {@code migrations} turns later boots into one indexed lookup. Each write is also
 * conditional on the row's {@code updatedAt}: runners start after the web server,
 * and an article saved between the read and the write already carries a list
 * derived from its new body, which a list derived from the old one must not
 * overwrite.
 */
@Slf4j
@Component
// After MarkdownToLexicalBackfill: the two are independent, but a fixed order
// keeps startup logs readable.
@Order(30)
@RequiredArgsConstructor
public class ArticleIssueKeysBackfill implements ApplicationRunner {

	static final String MARKER_ID = "article-issue-keys-with-digits";

	private static final String ARTICLES = "articles";
	private static final String DOC = "contentDoc";
	private static final String REFS = "referencedIssueKeys";
	private static final String UPDATED_AT = "updatedAt";

	/** Rows per bulk write, as in {@link MarkdownToLexicalBackfill}. */
	private static final int BATCH = 200;

	private final MongoTemplate mongo;
	private final RichTextService richText;

	@Override
	public void run(ApplicationArguments args) {
		if (alreadyDone()) return;
		MongoCollection<Document> articles = mongo.getCollection(ARTICLES);
		Bson filter = Filters.and(Filters.exists(DOC), Filters.ne(DOC, null), Filters.ne(DOC, ""));

		long updated = 0;
		boolean complete = true;
		List<Pending> batch = new ArrayList<>(BATCH);
		try (MongoCursor<Document> cursor = articles.find(filter)
				.projection(new Document("_id", 1).append(DOC, 1).append(REFS, 1).append(UPDATED_AT, 1))
				.batchSize(BATCH)
				.cursor()) {
			while (cursor.hasNext()) {
				Document row = cursor.next();
				List<String> derived = derive(row);
				if (derived == null || derived.equals(stored(row))) continue;
				batch.add(new Pending(row.get("_id"), new UpdateOneModel<>(unchangedSinceRead(row),
						new Document("$set", new Document(REFS, derived)))));
				if (batch.size() >= BATCH) {
					Flushed flushed = flush(articles, batch);
					updated += flushed.written();
					complete &= flushed.ok();
				}
			}
		}
		catch (RuntimeException ex) {
			log.error("ArticleIssueKeysBackfill: could not read articles; retrying on the next boot", ex);
			return;
		}
		Flushed flushed = flush(articles, batch);
		updated += flushed.written();
		complete &= flushed.ok();

		if (updated > 0) {
			log.info("ArticleIssueKeysBackfill: re-derived the issue backlinks of {} article(s)", updated);
		}
		if (complete) {
			markDone(updated);
		}
		else {
			log.warn("ArticleIssueKeysBackfill: a batch failed to write; leaving the migration "
					+ "unmarked so the next boot retries it");
		}
	}

	/** The list the current derivation produces, or {@code null} when the document is unreadable. */
	private List<String> derive(Document row) {
		if (!(row.get(DOC) instanceof String doc)) return null;
		try {
			return richText.fromLexical(doc).issueKeys();
		}
		catch (RuntimeException ex) {
			// Leave the row as it is: an unreadable document is not a reason to
			// clear the backlinks it has.
			log.warn("ArticleIssueKeysBackfill: skipped article {} (document unreadable)", row.get("_id"), ex);
			return null;
		}
	}

	private static List<?> stored(Document row) {
		return row.get(REFS) instanceof List<?> list ? list : List.of();
	}

	/** Matches the row only while it is still the version this run read. */
	private static Bson unchangedSinceRead(Document row) {
		Object updatedAt = row.get(UPDATED_AT);
		return Filters.and(Filters.eq("_id", row.get("_id")),
				updatedAt == null ? Filters.exists(UPDATED_AT, false) : Filters.eq(UPDATED_AT, updatedAt));
	}

	/** A pending write and the row id it belongs to, so a failed batch can name its rows. */
	private record Pending(Object id, WriteModel<Document> write) {
	}

	/** How one bulk write went. */
	private record Flushed(long written, boolean ok) {
	}

	/**
	 * Writes one batch. A failure is logged with the ids it covered and the run
	 * continues; letting it out of {@link #run} would stop the server from starting.
	 */
	private Flushed flush(MongoCollection<Document> articles, List<Pending> batch) {
		if (batch.isEmpty()) return new Flushed(0, true);
		try {
			List<WriteModel<Document>> writes = batch.stream().map(Pending::write).toList();
			return new Flushed(articles.bulkWrite(writes).getModifiedCount(), true);
		}
		catch (RuntimeException ex) {
			log.error("ArticleIssueKeysBackfill: batch of {} article(s) failed to write; ids={}",
					batch.size(), batch.stream().map(Pending::id).toList(), ex);
			return new Flushed(0, false);
		}
		finally {
			batch.clear();
		}
	}

	private boolean alreadyDone() {
		try {
			return mongo.getCollection(MarkdownToLexicalBackfill.MIGRATIONS)
					.find(new Document("_id", MARKER_ID)).limit(1).first() != null;
		}
		catch (RuntimeException ex) {
			log.warn("ArticleIssueKeysBackfill: could not read the completion marker", ex);
			return false;
		}
	}

	private void markDone(long updated) {
		try {
			mongo.getCollection(MarkdownToLexicalBackfill.MIGRATIONS).insertOne(new Document("_id", MARKER_ID)
					.append("completedAt", Instant.now())
					.append("updated", updated));
		}
		catch (RuntimeException ex) {
			log.warn("ArticleIssueKeysBackfill: could not write the completion marker; the next "
					+ "boot will scan again", ex);
		}
	}
}
