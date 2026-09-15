package com.ahmadre.hinata.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The completion markers of the one-time migrations that run on every start, kept in
 * {@value #COLLECTION}. A finished migration costs a later start one lookup by id instead of the
 * scan that asked what was left to do, which for most migrations has no index to answer it.
 *
 * <p>Neither reading nor writing a marker can stop the server from starting. A marker that cannot be
 * read counts as not there, which every migration survives because each writes only what is still
 * missing; a marker that cannot be written leaves the next start to run the migration again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationMarkers {

	/** Where the markers live. */
	public static final String COLLECTION = "migrations";

	private final MongoTemplate mongo;

	/** Whether the migration [id] completed on an earlier start. */
	public boolean done(String id) {
		try {
			return mongo.getCollection(COLLECTION).find(new Document("_id", id)).limit(1).first() != null;
		}
		catch (RuntimeException ex) {
			log.warn("Could not read the completion marker of the migration {}", id, ex);
			return false;
		}
	}

	/** Marks the migration [id] completed, with [details] of what it did. */
	public void markDone(String id, Document details) {
		Document marker = new Document("_id", id).append("completedAt", Instant.now());
		marker.putAll(details);
		try {
			mongo.getCollection(COLLECTION).insertOne(marker);
		}
		catch (RuntimeException ex) {
			log.warn("Could not write the completion marker of the migration {}; the next start runs it again",
					id, ex);
		}
	}
}
