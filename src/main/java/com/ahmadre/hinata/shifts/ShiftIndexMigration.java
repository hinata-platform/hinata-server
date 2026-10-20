package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Drops the module's retired indexes (HIN-27 review): {@code shifts.live_start}, which grew by
 * {@code _id} as {@code live_start_id} so the counter reconciliation reads its pages sorted, and
 * {@code shift_assignments.event}, which grew by {@code status} as {@code event_status}. An old
 * one would only cost every write a second copy of the same keys.
 *
 * <p>Asks the collections each start rather than keeping a marker, as the other index migrations
 * do: it stays right on an instance restored from an older backup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftIndexMigration implements ApplicationRunner {

	/** Collection to the retired index in it. */
	static final Map<String, String> RETIRED = Map.of("shifts", "live_start", "shift_assignments", "event");

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		drop();
	}

	int drop() {
		int dropped = 0;
		for (Map.Entry<String, String> retired : RETIRED.entrySet()) {
			if (!mongo.collectionExists(retired.getKey())) {
				continue;
			}
			for (Document index : mongo.getCollection(retired.getKey()).listIndexes()) {
				if (retired.getValue().equals(index.getString("name"))) {
					mongo.getCollection(retired.getKey()).dropIndex(retired.getValue());
					log.info("[shifts] dropped the retired index {}.{}", retired.getKey(), retired.getValue());
					dropped++;
				}
			}
		}
		return dropped;
	}
}
