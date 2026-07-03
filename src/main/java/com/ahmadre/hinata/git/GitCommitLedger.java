package com.ahmadre.hinata.git;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * A tiny append-only ledger of commits whose <em>side effects</em> (smart
 * commits + push automation) have already been applied, so the same commit is
 * only ever processed <strong>once</strong>.
 *
 * <p>Providers redeliver webhooks on timeout, and — crucially — the same commit
 * SHA is delivered again whenever a feature branch is merged into the default
 * branch (the merge push re-lists every commit). Without a guard, each redelivery
 * re-posts every {@code #comment}, re-logs every {@code #time} and re-runs every
 * transition. Recording is keyed by {@code provider|owner/repo|sha} and made
 * atomic via a unique {@code _id}, so two concurrent deliveries can't both win.
 */
@Service
@RequiredArgsConstructor
public class GitCommitLedger {

	private final MongoTemplate mongo;

	/**
	 * Marks a commit as processed, returning {@code true} the first time it is
	 * seen and {@code false} on every repeat. A blank SHA can't be deduplicated,
	 * so it is always treated as new.
	 */
	public boolean firstSight(String provider, String slug, String sha) {
		if (sha == null || sha.isBlank()) {
			return true;
		}
		String id = provider + "|" + slug + "|" + sha;
		try {
			mongo.insert(new Entry(id, Instant.now()));
			return true;
		}
		catch (DuplicateKeyException alreadyProcessed) {
			return false;
		}
	}

	@Document("git_processed_commits")
	record Entry(@Id String id, Instant at) {
	}
}
