package com.ahmadre.hinata.issue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fills the search text of the issues an older server saved without one. It runs after the other
 * migrations, so a key they rewrote is searched as it reads now.
 *
 * <p>Which issues lack the text is a question no index answers, so asking it reads every issue. A run
 * that filled every issue leaves a marker in {@code migrations}, and every later start costs one
 * lookup: every server from this one on writes the text with the issue. A run that could not write
 * leaves no marker and does not stop the server from starting, and the next start tries again.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class IssueSearchTextBackfill implements ApplicationRunner {

	/** Where completion markers live, and the id of this backfill's marker. */
	static final String MIGRATIONS = "migrations";
	static final String MARKER_ID = "issue-search-text";

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		if (alreadyDone()) {
			return;
		}
		long filled;
		try {
			filled = IssueSearchText.refresh(mongo, Criteria.where(IssueSearchText.FIELD).exists(false));
		}
		catch (RuntimeException ex) {
			log.warn("IssueSearchTextBackfill: could not fill the search text; the next start tries again", ex);
			return;
		}
		if (filled > 0) {
			log.info("IssueSearchTextBackfill: filled the search text of {} issue(s)", filled);
		}
		markDone(filled);
	}

	private boolean alreadyDone() {
		try {
			return mongo.getCollection(MIGRATIONS).find(new Document("_id", MARKER_ID)).limit(1).first() != null;
		}
		catch (RuntimeException ex) {
			// A marker that cannot be read is no reason to refuse to start: the backfill only writes
			// what is missing, so the worst case is asking once more.
			log.warn("IssueSearchTextBackfill: could not read the completion marker", ex);
			return false;
		}
	}

	private void markDone(long filled) {
		try {
			mongo.getCollection(MIGRATIONS).insertOne(new Document("_id", MARKER_ID)
					.append("completedAt", Instant.now())
					.append("filled", filled));
		}
		catch (RuntimeException ex) {
			log.warn("IssueSearchTextBackfill: could not write the completion marker; the next start asks again", ex);
		}
	}
}
