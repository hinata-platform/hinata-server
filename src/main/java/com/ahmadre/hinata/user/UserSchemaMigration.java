package com.ahmadre.hinata.user;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
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
 * One-time, idempotent backfill for the admin user-management invite lifecycle.
 * Existing accounts pre-date the {@code joinedAt} field; without it the admin
 * board would read every legacy user as a pending invite. This sets
 * {@code joinedAt = createdAt} (falling back to "now") for any user missing it,
 * so they correctly resolve to ACTIVE/DISABLED. Runs before typed reads and is a
 * no-op once converted / on fresh databases.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class UserSchemaMigration implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("users");
		// Pipeline update so each document gets its own createdAt copied into joinedAt.
		UpdateResult result = col.updateMany(
				new Document("joinedAt", new Document("$exists", false)),
				List.of(new Document("$set", new Document("joinedAt",
						new Document("$ifNull", List.of("$createdAt", "$$NOW"))))));
		if (result.getModifiedCount() > 0) {
			log.info("UserSchemaMigration: backfilled joinedAt on {} user document(s)",
					result.getModifiedCount());
		}
	}
}
