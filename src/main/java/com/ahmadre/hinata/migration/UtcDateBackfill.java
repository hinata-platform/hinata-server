package com.ahmadre.hinata.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * One-time, idempotent backfill that normalizes every persisted calendar date to
 * UTC midnight.
 *
 * <p>Spring Data maps a {@code LocalDate} to a BSON {@code Date} at start-of-day
 * in the JVM's default zone. Before the app was pinned to UTC, a host running in
 * e.g. Europe/Berlin (UTC+2) stored {@code 2026-07-06} as {@code 2026-07-05T22:00Z}
 * — so once the JVM reads it back in UTC it lands on the <em>previous</em>
 * calendar day. This migration snaps each such value to the nearest UTC midnight,
 * recovering the intended day.
 *
 * <p>The fix is expressed entirely server-side as an aggregation-pipeline update:
 * add 12h then truncate to the day, which <b>rounds to the nearest</b> midnight
 * (works for any zone offset within ±12h — covers both the dev host UTC+1/+2 and
 * any Westward offset). A {@code $expr} pre-filter only touches documents that are
 * <em>not already</em> at UTC midnight, so:
 * <ul>
 *   <li>it is a genuine no-op on an environment that was already UTC (prod runs in
 *       Docker, whose base image defaults to UTC — those dates are already 00:00Z);</li>
 *   <li>it is safe to run on every boot and safe to run twice.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UtcDateBackfill implements ApplicationRunner {

	/** Persisted {@code LocalDate}-mapped fields, grouped by collection. */
	private static final Map<String, List<String>> TARGETS = Map.of(
			"issues", List.of("startDate", "dueDate"),
			"sprints", List.of("startDate", "endDate"),
			"work_items", List.of("date"));

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		long changed = 0;
		for (Map.Entry<String, List<String>> entry : TARGETS.entrySet()) {
			String collection = entry.getKey();
			MongoCollection<Document> coll = mongo.getCollection(collection);
			for (String field : entry.getValue()) {
				UpdateResult result = coll.updateMany(offMidnight(field), snapToUtcMidnight(field));
				if (result.getModifiedCount() > 0) {
					log.info("UtcDateBackfill: snapped {} value(s) in {}.{} to UTC midnight",
							result.getModifiedCount(), collection, field);
				}
				changed += result.getModifiedCount();
			}
		}
		if (changed > 0) {
			log.info("UtcDateBackfill: normalized {} calendar-date field(s) to UTC midnight", changed);
		}
	}

	/** Matches date values whose UTC time-of-day is not exactly 00:00 (i.e. stored under a non-UTC zone). */
	private static Bson offMidnight(String field) {
		String ref = "$" + field;
		Document utcFloor = new Document("$dateTrunc",
				new Document("date", ref).append("unit", "day").append("timezone", "UTC"));
		return Filters.and(
				Filters.type(field, "date"),
				new Document("$expr", new Document("$ne", List.of(ref, utcFloor))));
	}

	/** Pipeline update: round the date to the NEAREST UTC midnight (add 12h, then truncate to the day). */
	private static List<Bson> snapToUtcMidnight(String field) {
		String ref = "$" + field;
		Document shifted = new Document("$dateAdd",
				new Document("startDate", ref).append("unit", "hour").append("amount", 12L));
		Document rounded = new Document("$dateTrunc",
				new Document("date", shifted).append("unit", "day").append("timezone", "UTC"));
		return List.of(new Document("$set", new Document(field, rounded)));
	}
}
