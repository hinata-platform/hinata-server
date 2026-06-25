package com.ahmadre.hinata.project;

import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * One-time, idempotent reconciliation of each issue's {@code resolvedAt} against
 * its project's current {@code resolvedStates}. Historically a workflow / resolved-
 * states change did not recompute the flag, so an issue could keep a stale
 * {@code resolvedAt} after dropping back to an open state — which wrongly struck a
 * still-open sub-task through. Runs on boot (after the schema migrations); a no-op
 * once everything is consistent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolvedAtReconciliation implements ApplicationRunner {

	private static final String PROJECT_ID = "projectId";
	private static final String STATE = "state";
	private static final String RESOLVED_AT = "resolvedAt";

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		long changed = 0;
		for (Project project : mongo.findAll(Project.class)) {
			List<String> resolved = project.getResolvedStates();
			if (resolved == null || resolved.isEmpty()) continue;
			UpdateResult set = mongo.updateMulti(
					new Query(Criteria.where(PROJECT_ID).is(project.getId())
							.and(STATE).in(resolved).and(RESOLVED_AT).is(null)),
					new Update().set(RESOLVED_AT, Instant.now()), "issues");
			UpdateResult cleared = mongo.updateMulti(
					new Query(Criteria.where(PROJECT_ID).is(project.getId())
							.and(STATE).nin(resolved).and(RESOLVED_AT).ne(null)),
					new Update().unset(RESOLVED_AT), "issues");
			changed += set.getModifiedCount() + cleared.getModifiedCount();
		}
		if (changed > 0) {
			log.info("ResolvedAtReconciliation: corrected resolvedAt on {} issue document(s)", changed);
		}
	}
}
