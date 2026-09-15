package com.ahmadre.hinata.issue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Fills the search text of the issues an older server saved without one. It runs after the other
 * migrations, so a key they rewrote is searched as it reads now, and does nothing once every issue has
 * its text.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class IssueSearchTextBackfill implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		long filled = IssueSearchText.refresh(mongo, Criteria.where(IssueSearchText.FIELD).exists(false));
		if (filled > 0) {
			log.info("IssueSearchTextBackfill: filled the search text of {} issue(s)", filled);
		}
	}
}
