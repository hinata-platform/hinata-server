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
 * Legacy accounts pre-date the {@code joinedAt} field; this sets
 * {@code joinedAt = createdAt} (falling back to "now") so they resolve to
 * ACTIVE/DISABLED. Runs before typed reads and is a no-op once converted / on
 * fresh databases.
 *
 * IMPORTANT: a pending invite is exactly {@code invitedAt set && joinedAt
 * absent}, so it MUST be excluded — otherwise every server restart would stamp
 * {@code joinedAt} on open invites and silently mark them as joined (breaking
 * resend and re-invite). Only legacy users (no {@code invitedAt}) are touched.
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
		// Legacy users only: missing joinedAt AND never invited. Pending invites
		// (invitedAt present, joinedAt absent) are deliberately left untouched.
		// Pipeline update so each document gets its own createdAt copied into joinedAt.
		UpdateResult result = col.updateMany(
				new Document("joinedAt", new Document("$exists", false))
						.append("invitedAt", new Document("$exists", false)),
				List.of(new Document("$set", new Document("joinedAt",
						new Document("$ifNull", List.of("$createdAt", "$$NOW"))))));
		if (result.getModifiedCount() > 0) {
			log.info("UserSchemaMigration: backfilled joinedAt on {} user document(s)",
					result.getModifiedCount());
		}

		// Newly-added primitive boolean fields are absent on legacy documents; a
		// missing value maps to null and Spring Data cannot bind null to a
		// primitive constructor parameter (breaking EVERY typed User read, incl.
		// login). Backfill each with its model default so old docs deserialize.
		backfillBoolean(col, "awaitingApproval", false); // self-registration approval gate
		backfillBoolean(col, "emailVerified", true);     // default: existing accounts are verified
	}

	/** Sets {@code field = value} on every document where it is currently absent. */
	private void backfillBoolean(MongoCollection<Document> col, String field, boolean value) {
		UpdateResult result = col.updateMany(
				new Document(field, new Document("$exists", false)),
				new Document("$set", new Document(field, value)));
		if (result.getModifiedCount() > 0) {
			log.info("UserSchemaMigration: backfilled {}={} on {} user document(s)",
					field, value, result.getModifiedCount());
		}
	}
}
