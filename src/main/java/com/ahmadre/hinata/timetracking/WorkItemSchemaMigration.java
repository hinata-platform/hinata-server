package com.ahmadre.hinata.timetracking;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Projections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time, idempotent backfills of the work-item schema, in the pattern of
 * {@code IssueSchemaMigration}: safe on every boot, a no-op once through.
 *
 * <ul>
 *   <li>{@code source}, {@code billable}, {@code tags}: documents written before
 *       2.0 lack the fields. Readers tolerate that, but an index or a filter can
 *       only ask {@code billable == false} as an equality if the field exists,
 *       so absent values are written out as their documented defaults.</li>
 *   <li>Legacy remainders: before 2.0 a {@code #time} smart commit bumped the
 *       issue's {@code spentMinutes} directly, without an entry. Now that the
 *       counter is <em>derived</em> from the entries, that time would vanish
 *       on the next sync. So every issue whose counter exceeds the sum of its
 *       entries gets one {@link WorkItem.Source#LEGACY} entry for the
 *       difference — attributed to nobody, because nobody is known.</li>
 * </ul>
 *
 * <p>Idempotent by construction: after the first run every remainder is zero,
 * and a remainder is the only thing that creates an entry. Issues are walked
 * with a cursor in batches — never loaded whole — and each batch costs one
 * aggregation over the entries it references.
 */
@Slf4j
@Component
// After the issue backfills, before anything that reads spentMinutes.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class WorkItemSchemaMigration implements ApplicationRunner {

	static final int BATCH = 500;
	static final String LEGACY_ACTIVITY = "Development";

	private final MongoTemplate mongo;
	private final Clock clock;

	/** What one run did, for the log and for tests. */
	public record Result(long defaultsBackfilled, long legacyEntriesCreated, long legacyMinutes) {
	}

	@Override
	public void run(ApplicationArguments args) {
		migrate();
	}

	public Result migrate() {
		long defaults = backfillDefaults(mongo.getCollection("work_items"));
		long[] legacy = backfillLegacyRemainders();
		if (defaults > 0 || legacy[0] > 0) {
			log.info("WorkItemSchemaMigration: backfilled defaults on {} entry(ies), created {} legacy "
					+ "entry(ies) worth {} minute(s)", defaults, legacy[0], legacy[1]);
		}
		return new Result(defaults, legacy[0], legacy[1]);
	}

	/**
	 * Writes the documented defaults wherever a field is absent or null. Three
	 * statements that match nothing at all on a database that has been through
	 * this once.
	 */
	private static long backfillDefaults(MongoCollection<Document> col) {
		long changed = 0;
		changed += col.updateMany(new Document("source", null),
				new Document("$set", new Document("source", WorkItem.Source.APP.name()))).getModifiedCount();
		changed += col.updateMany(new Document("billable", null),
				new Document("$set", new Document("billable", false))).getModifiedCount();
		changed += col.updateMany(new Document("tags", null),
				new Document("$set", new Document("tags", List.of()))).getModifiedCount();
		return changed;
	}

	/** Returns {entries created, minutes covered}. */
	private long[] backfillLegacyRemainders() {
		long created = 0;
		long minutes = 0;
		MongoCollection<Document> issues = mongo.getCollection("issues");
		List<Document> batch = new ArrayList<>(BATCH);
		try (MongoCursor<Document> cursor = issues
				.find(new Document("spentMinutes", new Document("$gt", 0)))
				.projection(Projections.include("_id", "projectId", "spentMinutes", "updatedAt", "createdAt"))
				.batchSize(BATCH)
				.iterator()) {
			while (cursor.hasNext()) {
				batch.add(cursor.next());
				if (batch.size() == BATCH) {
					long[] done = settle(batch);
					created += done[0];
					minutes += done[1];
					batch.clear();
				}
			}
		}
		if (!batch.isEmpty()) {
			long[] done = settle(batch);
			created += done[0];
			minutes += done[1];
		}
		return new long[] { created, minutes };
	}

	/** One batch: sum the entries of these issues in one query, insert the remainders. */
	private long[] settle(List<Document> issues) {
		List<String> ids = issues.stream().map(doc -> doc.get("_id").toString()).toList();
		Map<String, Long> tracked = trackedMinutes(ids);
		long created = 0;
		long minutes = 0;
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		for (Document issue : issues) {
			String issueId = issue.get("_id").toString();
			long spent = ((Number) issue.get("spentMinutes")).longValue();
			long remainder = spent - tracked.getOrDefault(issueId, 0L);
			if (remainder <= 0) {
				continue;
			}
			mongo.insert(WorkItem.builder()
					.issueId(issueId)
					.projectId(issue.getString("projectId"))
					.userId(null)
					.date(dayOf(issue, today))
					.durationMinutes((int) Math.min(remainder, Integer.MAX_VALUE))
					.activityType(LEGACY_ACTIVITY)
					.description("")
					.billable(false)
					.tags(new ArrayList<>())
					.source(WorkItem.Source.LEGACY)
					.build());
			created++;
			minutes += remainder;
		}
		return new long[] { created, minutes };
	}

	/** Σ durationMinutes per issue for the given issue ids, straight from Mongo. */
	private Map<String, Long> trackedMinutes(List<String> issueIds) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("issueId").in(issueIds)),
				Aggregation.group("issueId").sum("durationMinutes").as("total"));
		Map<String, Long> sums = new HashMap<>();
		for (Document row : mongo.aggregate(aggregation, WorkItem.class, Document.class)) {
			Object id = row.get("_id");
			Object total = row.get("total");
			if (id != null && total instanceof Number number) {
				sums.put(id.toString(), number.longValue());
			}
		}
		return sums;
	}

	/** The issue's last change as a UTC day; its creation if it was never changed; today if neither is known. */
	private static LocalDate dayOf(Document issue, LocalDate today) {
		Date stamp = issue.get("updatedAt") instanceof Date updated ? updated
				: issue.get("createdAt") instanceof Date created ? created : null;
		return stamp == null ? today : LocalDate.ofInstant(stamp.toInstant(), ZoneOffset.UTC);
	}
}
