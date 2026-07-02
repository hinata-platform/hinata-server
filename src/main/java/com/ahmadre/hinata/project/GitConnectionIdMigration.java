package com.ahmadre.hinata.project;

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

import java.util.UUID;

/**
 * One-time, idempotent backfill of the per-connection {@code git.id}. Multi-repo
 * support (a project can connect several repositories) references each
 * connection by a stable id for re-sync / disconnect. Repositories connected
 * before that id existed get one assigned here so the app can target them.
 * Runs against raw BSON before any typed read; no-op once every primary
 * connection has an id / on fresh DBs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GitConnectionIdMigration implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("projects");
		int migrated = 0;
		for (Document project : col.find(new Document("git", new Document("$ne", null)))) {
			Document git = project.get("git", Document.class);
			if (git == null || (git.getString("id") != null && !git.getString("id").isBlank())) {
				continue;
			}
			git.put("id", UUID.randomUUID().toString().substring(0, 8));
			col.updateOne(new Document("_id", project.get("_id")),
					new Document("$set", new Document("git", git)));
			migrated++;
		}
		if (migrated > 0) {
			log.info("GitConnectionIdMigration: assigned ids to {} primary git connection(s)", migrated);
		}
	}
}
