package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the index {@code device_tokens.userId}: the {@code user_kind} index
 * (HIN-103) starts with the same field and answers every query the old one did,
 * so keeping both would only cost every write a second copy of the same key.
 *
 * <p>Asks the collection each start rather than keeping a marker, like
 * {@code WorkItemIndexMigration}: listing the indexes of one collection is as
 * cheap as reading a marker, and it stays right on an instance restored from a
 * backup taken before the change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTokenIndexMigration implements ApplicationRunner {

	static final String RETIRED = "userId";

	/** MongoDB's error code for dropping an index that is not there. */
	private static final int INDEX_NOT_FOUND = 27;

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		drop();
	}

	boolean drop() {
		String collection = mongo.getCollectionName(DeviceToken.class);
		for (Document index : mongo.getCollection(collection).listIndexes()) {
			if (RETIRED.equals(index.getString("name"))) {
				try {
					mongo.getCollection(collection).dropIndex(RETIRED);
				}
				catch (com.mongodb.MongoCommandException gone) {
					// A second instance starting at the same moment dropped it first.
					if (gone.getErrorCode() != INDEX_NOT_FOUND) throw gone;
				}
				log.info("[devices] dropped the retired index device_tokens.{}", RETIRED);
				return true;
			}
		}
		return false;
	}
}
