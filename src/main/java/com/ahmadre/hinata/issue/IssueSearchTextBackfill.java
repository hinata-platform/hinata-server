package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.migration.MigrationMarkers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Fills the search text of the issues an older server saved without one. It runs after the other
 * migrations, so a key they rewrote is searched as it reads now.
 *
 * <p>Which issues lack the text is a question no index answers, so asking it reads every issue. A run
 * that filled every issue leaves a marker ({@link MigrationMarkers}), and every later start costs one
 * lookup: every server from this one on writes the text with the issue. A run that could not write
 * leaves no marker and does not stop the server from starting, and the next start tries again.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class IssueSearchTextBackfill implements ApplicationRunner {

	/** The id of this backfill's completion marker. */
	static final String MARKER_ID = "issue-search-text";

	private final MongoTemplate mongo;
	private final MigrationMarkers markers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) {
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
		markers.markDone(MARKER_ID, new Document("filled", filled));
	}
}
