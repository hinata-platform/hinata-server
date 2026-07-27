package com.ahmadre.hinata.migration;

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
 *       resumes exactly where it stopped.</li>
 *   <li><b>Non-destructive.</b> The markdown is not deleted — it is replaced by
 *       its own plain-text projection, which is what every plain-text consumer
 *       (search, teasers, exports) wanted from it in the first place. Nothing
 *       reads markdown syntax out of that field.</li>
 *   <li><b>Bounded memory.</b> Rows stream in batches rather than loading a
 *       collection into the heap, because an install with a large knowledge base
 *       is exactly the one that cannot afford otherwise.</li>
 * </ul>
 *
 * <p>A row that fails to convert is logged and skipped rather than aborting the
 * run: one unparseable body must not stop the other ten thousand, and skipping
 * leaves it in exactly the state a re-run can retry.
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

	private final MongoTemplate mongo;
	private final RichTextService richText;

	@Override
	public void run(ApplicationArguments args) {
		for (Target target : TARGETS) {
			long migrated = migrate(target);
			if (migrated > 0) {
				log.info("MarkdownToLexicalBackfill: converted {} {} document(s) in {}",
						migrated, target.textField(), target.collection());
			}
		}
	}

	private long migrate(Target target) {
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
		List<WriteModel<Document>> batch = new ArrayList<>(BATCH);
		try (MongoCursor<Document> cursor = collection
				.find(filter)
				.projection(new Document("_id", 1).append(target.textField(), 1))
				.batchSize(BATCH)
				.cursor()) {
			while (cursor.hasNext()) {
				Document row = cursor.next();
				UpdateOneModel<Document> write = convert(row, target);
				if (write != null) batch.add(write);
				if (batch.size() >= BATCH) {
					migrated += flush(collection, batch);
				}
			}
		}
		migrated += flush(collection, batch);
		return migrated;
	}

	/** Converts one row, or {@code null} when it cannot be converted. */
	private UpdateOneModel<Document> convert(Document row, Target target) {
		Object markdown = row.get(target.textField());
		if (!(markdown instanceof String source) || source.isBlank()) return null;
		try {
			RichText converted = richText.fromMarkdown(source);
			if (converted.doc() == null) return null;
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

	private long flush(MongoCollection<Document> collection, List<WriteModel<Document>> batch) {
		if (batch.isEmpty()) return 0;
		long written = collection.bulkWrite(batch).getModifiedCount();
		batch.clear();
		return written;
	}
}
