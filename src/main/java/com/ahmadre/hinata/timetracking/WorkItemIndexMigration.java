package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the index {@code work_items.date_user_project} (HIN-93): its keys grew by
 * {@code durationMinutes} under a new name ({@link WorkItem#DATE_USER_PROJECT_MINUTES_INDEX}),
 * and the old one would only cost every write a second copy of the same keys.
 *
 * <p>Asks the collection each start rather than keeping a marker: listing the indexes of one
 * collection is as cheap as reading a marker, and it stays right on an instance restored from a
 * backup taken before the change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkItemIndexMigration implements ApplicationRunner {

	static final String RETIRED = "date_user_project";

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		drop();
	}

	boolean drop() {
		for (Document index : mongo.getCollection(mongo.getCollectionName(WorkItem.class)).listIndexes()) {
			if (RETIRED.equals(index.getString("name"))) {
				mongo.getCollection(mongo.getCollectionName(WorkItem.class)).dropIndex(RETIRED);
				log.info("[time] dropped the retired index work_items.{}", RETIRED);
				return true;
			}
		}
		return false;
	}
}
