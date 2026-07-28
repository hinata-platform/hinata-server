package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.richtext.LexicalJson;
import com.ahmadre.hinata.richtext.RichText;
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
 * One-time, idempotent migration of stored markdown to Lexical documents.
 *
 * <p>Content used to be persisted as markdown in {@code Issue.description},
 * {@code Article.content} and {@code IssueComment.text}. It is now persisted as
 * a Lexical document in a sibling field, with those original fields holding the
 * plain text derived from it. This walks every document that has content but no
 * document yet, converts it with the same {@link RichTextService} that MCP and
 * e-mail ingest go through, and writes both fields together.
 *
 * <p>Three properties make this safe to leave in place forever:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The filter only matches rows whose document field is
 *       missing or null, so a second run is a no-op and an interrupted run
 *       resumes exactly where it stopped. A completion marker in
 *       {@code migrations} turns a finished migration into one indexed lookup
 *       instead of a full scan of three collections on every boot.</li>
 *   <li><b>Non-fatal.</b> Neither a row that cannot be converted nor a batch that
 *       cannot be written stops the run — and neither stops the server from
 *       starting, which is what an unguarded {@code ApplicationRunner} would do
 *       for every boot from then on.</li>
 *   <li><b>Bounded memory.</b> Rows stream in batches rather than loading a
 *       collection into the heap, because an install with a large knowledge base
 *       is exactly the one that cannot afford otherwise.</li>
 * </ul>
 */
@Slf4j
@Component
// After UtcDateBackfill: both are independent, but a deterministic order keeps
// startup logs readable.
@Order(20)
@RequiredArgsConstructor
public class MarkdownToLexicalBackfill implements ApplicationRunner {

	/**
	 * One field pair to migrate: the legacy markdown field and its new document.
	 * {@code refsField} is set for content that also carries derived issue
	 * backlinks — without it an upgraded knowledge base would answer "no articles
	 * reference this issue" until every article happened to be edited again.
	 */
	private record Target(String collection, String textField, String docField, String refsField) {
	}

	private static final List<Target> TARGETS = List.of(
			new Target("issues", "description", "descriptionDoc", null),
			new Target("articles", "content", "contentDoc", "referencedIssueKeys"),
			new Target("issue_comments", "text", "textDoc", null));

	/** Rows per bulk write. Large enough to be fast, small enough to stay bounded. */
	private static final int BATCH = 200;

	/**
	 * Largest converted document handed to Mongo. A converted body is capped by
	 * {@link RichTextService} already; this states the ceiling locally so an
	 * oversized row is reported as "too large" rather than as a BSON write error —
	 * Mongo's own limit is 16 MB and an ordered bulk write drops the rest of the
	 * batch when one member of it is refused.
	 */
	private static final int MAX_DOC_CHARS = LexicalJson.MAX_JSON_CHARS;

	/** Where completion markers live, and the id of this migration's marker. */
	static final String MIGRATIONS = "migrations";
	static final String MARKER_ID = "markdown-to-lexical";

	private final MongoTemplate mongo;
	private final RichTextService richText;

	@Override
	public void run(ApplicationArguments args) {
		if (alreadyDone()) return;
		boolean complete = true;
		long converted = 0;
		for (Target target : TARGETS) {
			Result result = migrate(target);
			converted += result.migrated();
			complete &= result.complete();
			if (result.migrated() > 0) {
				log.info("MarkdownToLexicalBackfill: converted {} {} document(s) in {}",
						result.migrated(), target.textField(), target.collection());
			}
		}
		if (complete) {
			markDone(converted);
		}
		else {
			log.warn("MarkdownToLexicalBackfill: at least one batch failed to write; leaving the "
					+ "migration unmarked so the next boot retries it");
		}
	}

	/**
	 * Whether a previous boot already completed the migration. This is the point of
	 * the marker: the filter below has no supporting index, so without it every
	 * boot pays a full scan of three collections before the server is ready, for
	 * the rest of the installation's life.
	 */
	private boolean alreadyDone() {
		try {
			return mongo.getCollection(MIGRATIONS)
					.find(new Document("_id", MARKER_ID)).limit(1).first() != null;
		}
		catch (RuntimeException ex) {
			// A marker that cannot be read is not a reason to refuse to start; the
			// migration is idempotent, so the worst case is running it again.
			log.warn("MarkdownToLexicalBackfill: could not read the completion marker", ex);
			return false;
		}
	}

	private void markDone(long converted) {
		try {
			mongo.getCollection(MIGRATIONS).insertOne(new Document("_id", MARKER_ID)
					.append("completedAt", Instant.now())
					.append("converted", converted));
		}
		catch (RuntimeException ex) {
			log.warn("MarkdownToLexicalBackfill: could not write the completion marker; the next "
					+ "boot will scan again", ex);
		}
	}

	/** How one collection's pass went: how many rows moved, and whether all writes landed. */
	private record Result(long migrated, boolean complete) {
	}

	/** A pending write and the row id it belongs to, so a failed batch can name its rows. */
	private record Pending(Object id, WriteModel<Document> write) {
	}

	private Result migrate(Target target) {
		MongoCollection<Document> collection = mongo.getCollection(target.collection());
		// Has readable markdown, has no Lexical document yet.
		Bson filter = Filters.and(
				Filters.exists(target.textField()),
				Filters.ne(target.textField(), null),
				Filters.ne(target.textField(), ""),
				Filters.or(
						Filters.exists(target.docField(), false),
						Filters.eq(target.docField(), null)));

		long migrated = 0;
		boolean complete = true;
		List<Pending> batch = new ArrayList<>(BATCH);
		try (MongoCursor<Document> cursor = collection
				.find(filter)
				.projection(new Document("_id", 1).append(target.textField(), 1))
				.batchSize(BATCH)
				.cursor()) {
			while (cursor.hasNext()) {
				Document row = cursor.next();
				UpdateOneModel<Document> write = convert(row, target);
				if (write != null) batch.add(new Pending(row.get("_id"), write));
				if (batch.size() >= BATCH) {
					Flushed flushed = flush(collection, batch, target);
					migrated += flushed.written();
					complete &= flushed.ok();
				}
			}
		}
		Flushed flushed = flush(collection, batch, target);
		migrated += flushed.written();
		complete &= flushed.ok();
		return new Result(migrated, complete);
	}

	/** Converts one row, or {@code null} when it cannot be converted. */
	private UpdateOneModel<Document> convert(Document row, Target target) {
		Object markdown = row.get(target.textField());
		if (!(markdown instanceof String source) || source.isBlank()) return null;
		try {
			// Stored markdown predates the input bound live callers are held to, so
			// it is converted under the read bound instead of being skipped forever.
			RichText converted = richText.fromStoredMarkdown(source);
			if (converted.doc() == null) return null;
			if (converted.doc().length() > MAX_DOC_CHARS) {
				log.warn("MarkdownToLexicalBackfill: skipped {} {} (converted document is {} chars, "
						+ "over the {} ceiling)", target.collection(), row.get("_id"),
						converted.doc().length(), MAX_DOC_CHARS);
				return null;
			}
			Document set = new Document(target.docField(), converted.doc())
					.append(target.textField(), converted.text());
			if (target.refsField() != null) set.append(target.refsField(), converted.issueKeys());
			return new UpdateOneModel<>(Filters.eq("_id", row.get("_id")),
					new Document("$set", set));
		}
		catch (RuntimeException ex) {
			// Leave the row untouched so a later run can retry it, and say which one
			// it was — a silent skip here would be an invisible hole in the data.
			log.warn("MarkdownToLexicalBackfill: skipped {} {} (conversion failed)",
					target.collection(), row.get("_id"), ex);
			return null;
		}
	}

	/** How one bulk write went. */
	private record Flushed(long written, boolean ok) {
	}

	/**
	 * Writes one batch. A write failure — a stepdown during a rolling restart, a
	 * lost socket, one refused document — is logged with the ids it covered and the
	 * run continues. Letting it out of {@link #run} would make Spring close the
	 * context: the server would not start, and would not start on the next boot
	 * either if the cause is a row rather than the weather.
	 */
	private Flushed flush(MongoCollection<Document> collection, List<Pending> batch, Target target) {
		if (batch.isEmpty()) return new Flushed(0, true);
		try {
			List<WriteModel<Document>> writes = batch.stream().map(Pending::write).toList();
			return new Flushed(collection.bulkWrite(writes).getModifiedCount(), true);
		}
		catch (RuntimeException ex) {
			log.error("MarkdownToLexicalBackfill: batch of {} row(s) in {} failed to write; "
					+ "leaving them for the next boot. ids={}",
					batch.size(), target.collection(), batch.stream().map(Pending::id).toList(), ex);
			return new Flushed(0, false);
		}
		finally {
			batch.clear();
		}
	}
}
