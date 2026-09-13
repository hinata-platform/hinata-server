package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.TimePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-time move of the correction requests made before HIN-89 into their own
 * collection.
 *
 * <p>Until this stage a request existed only as its audit record. The inbox, the
 * one-per-day throttle and the answer now read {@link TimeCorrectionRequest}, so a
 * request somebody made last week would otherwise vanish from every administrator's
 * view on the day this ships. Each record becomes a request with the audit record's
 * id — which makes the move idempotent — and a completion marker in
 * {@code migrations} turns later boots into one indexed lookup. Non-fatal like the
 * other backfills: a record that cannot be moved is logged and skipped, and a run
 * that could not read at all leaves the marker unset so the next boot tries again.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class TimeCorrectionRequestBackfill implements ApplicationRunner {

	static final String MARKER_ID = "time-correction-requests-from-audit";

	/** The collection every backfill keeps its completion marker in. */
	private static final String MIGRATIONS = "migrations";

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		if (alreadyDone()) {
			return;
		}
		long moved = 0;
		Query query = Query.query(Criteria.where("action").is(AuditAction.TIME_CORRECTION_REQUESTED));
		try (Stream<AuditLog> records = mongo.stream(query, AuditLog.class)) {
			for (AuditLog record : (Iterable<AuditLog>) records::iterator) {
				TimeCorrectionRequest request = requestOf(record);
				if (request == null) {
					continue;
				}
				try {
					mongo.insert(request);
					moved++;
				}
				catch (DuplicateKeyException alreadyThere) {
					// Moved on an earlier boot, or a second request for the same entry on
					// the same day — which the old throttle already prevented.
				}
			}
		}
		catch (RuntimeException ex) {
			log.error("TimeCorrectionRequestBackfill: could not read the audit log; retrying on the next boot", ex);
			return;
		}
		if (moved > 0) {
			log.info("TimeCorrectionRequestBackfill: moved {} correction request(s) into their own collection",
					moved);
		}
		markDone(moved);
	}

	private static TimeCorrectionRequest requestOf(AuditLog record) {
		Map<String, String> meta = record.getMetadata();
		if (meta == null || meta.get("workItem") == null || record.getTimestamp() == null) {
			return null;
		}
		try {
			String date = meta.get("date");
			String reason = meta.get("reason");
			return TimeCorrectionRequest.builder()
					.id(record.getId())
					.kind(TimeCorrectionRequest.Kind.ENTRY)
					.userId(record.getActorId())
					.workItemId(meta.get("workItem"))
					.date(date == null || "null".equals(date) ? null : LocalDate.parse(date))
					.projectId(meta.get("project"))
					.reason(reason == null ? null : TimePolicy.LockReason.valueOf(reason))
					.note(meta.get("note"))
					.day(LocalDate.ofInstant(record.getTimestamp(), ZoneOffset.UTC))
					.createdAt(record.getTimestamp())
					.build();
		}
		catch (RuntimeException unreadable) {
			log.warn("TimeCorrectionRequestBackfill: skipped audit record {} (unreadable metadata)",
					record.getId());
			return null;
		}
	}

	private boolean alreadyDone() {
		try {
			return mongo.getCollection(MIGRATIONS)
					.find(new Document("_id", MARKER_ID)).limit(1).first() != null;
		}
		catch (RuntimeException ex) {
			log.warn("TimeCorrectionRequestBackfill: could not read the completion marker", ex);
			return false;
		}
	}

	private void markDone(long moved) {
		try {
			mongo.getCollection(MIGRATIONS).insertOne(new Document("_id", MARKER_ID)
					.append("completedAt", Instant.now())
					.append("moved", moved));
		}
		catch (RuntimeException ex) {
			log.warn("TimeCorrectionRequestBackfill: could not write the completion marker; the next "
					+ "boot will move again (idempotently)", ex);
		}
	}
}
