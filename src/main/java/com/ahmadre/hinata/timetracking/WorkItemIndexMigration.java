package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the retired indexes of {@code work_items}: {@code date_user_project} (HIN-93) and
 * {@code project_date} (HIN-156). The keys of each grew by {@code durationMinutes} under a new name
 * ({@link WorkItem#DATE_USER_PROJECT_MINUTES_INDEX}, {@link WorkItem#PROJECT_DATE_MINUTES_INDEX}),
 * and an old one would only cost every write a second copy of the same keys.
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
	static final String RETIRED_PROJECT_DATE = "project_date";

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		drop();
	}

	boolean drop() {
		boolean dropped = false;
		for (Document index : mongo.getCollection(mongo.getCollectionName(WorkItem.class)).listIndexes()) {
			String name = index.getString("name");
			if (RETIRED.equals(name) || RETIRED_PROJECT_DATE.equals(name)) {
				mongo.getCollection(mongo.getCollectionName(WorkItem.class)).dropIndex(name);
				log.info("[time] dropped the retired index work_items.{}", name);
				dropped = true;
			}
		}
		return dropped;
	}
}
