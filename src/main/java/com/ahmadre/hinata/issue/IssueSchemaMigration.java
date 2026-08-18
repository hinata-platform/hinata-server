package com.ahmadre.hinata.issue;

import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time, idempotent backfills of the issue schema, run against raw BSON before
 * any typed read; no-ops once converted / on fresh DBs.
 *
 * <ul>
 *   <li>{@code assigneeIds}: older documents stored only the single
 *       {@code assigneeId}. Seeding the array from it makes membership queries
 *       ("assigned to me", including secondary assignees) and the multi-assignee
 *       picker work uniformly.</li>
 *   <li>{@code archived}: documents written before the field existed simply lack
 *       it. That is invisible to a reader — {@code isArchived()} reads a missing
 *       field as false — but not to an index: a query can only ask
 *       {@code archived == false} as an <em>equality</em>, the bound the watch
 *       list's compound index needs, if the field is actually there.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IssueSchemaMigration implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("issues");
		backfillArchived(col);
		int migrated = 0;
		for (Document doc : col.find()) {
			Object existing = doc.get("assigneeIds");
			boolean hasList = existing instanceof List<?> list && !list.isEmpty();
			if (hasList) continue;
			Object assignee = doc.get("assigneeId");
			List<String> ids = (assignee instanceof String s && !s.isBlank())
					? List.of(s) : List.of();
			col.updateOne(new Document("_id", doc.get("_id")),
					new Document("$set", new Document("assigneeIds", ids)));
			migrated++;
		}
		if (migrated > 0) {
			log.info("IssueSchemaMigration: backfilled assigneeIds on {} issue document(s)", migrated);
		}
	}

	/**
	 * Writes an explicit {@code archived: false} wherever the field is absent or
	 * null. One statement rather than a loop: {@code {archived: null}} matches both
	 * cases, and matches nothing at all on a database that has already been through
	 * this, so it costs a single index-less-but-tiny query per boot.
	 */
	private void backfillArchived(MongoCollection<Document> col) {
		long normalized = col.updateMany(new Document("archived", null),
				new Document("$set", new Document("archived", false))).getModifiedCount();
		if (normalized > 0) {
			log.info("IssueSchemaMigration: set archived=false on {} issue document(s)", normalized);
		}
	}
}
