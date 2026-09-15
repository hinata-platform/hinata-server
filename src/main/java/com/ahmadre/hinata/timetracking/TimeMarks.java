package com.ahmadre.hinata.timetracking;

import com.mongodb.bulk.BulkWriteError;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The claims behind reminders and alerts, in {@code time_marks}. See {@link TimeMark} for why a
 * claim is an insert and nothing more.
 */
@Component
@RequiredArgsConstructor
class TimeMarks {

	private static final String REMINDER = "reminder:";

	/** The server's answer to an insert whose key is taken. */
	private static final int DUPLICATE_KEY = 11000;

	private final MongoTemplate mongo;
	private final Clock clock;

	/** The key of one person's reminder of one kind for the period starting on [periodStart]. */
	static String reminderKey(String userId, String kind, LocalDate periodStart) {
		return REMINDER + userId + ':' + kind + ':' + periodStart;
	}

	/** The key of one alert: a subject crossing one threshold. */
	static String alertKey(String kind, String subjectId, int percent) {
		return "alert:" + kind + ':' + subjectId + ':' + percent;
	}

	/** Which of [ids] are taken already: one read for a batch, before anyone claims. */
	Set<String> existing(Collection<String> ids) {
		if (ids.isEmpty()) {
			return Set.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include("_id");
		Set<String> taken = new HashSet<>();
		mongo.find(query, TimeMark.class).forEach(mark -> taken.add(mark.getId()));
		return taken;
	}

	/**
	 * Takes every one of [ids] nobody holds yet, in one round trip, and returns those taken. A key
	 * somebody took first, here or on another instance, is simply not among them.
	 */
	Set<String> claimAll(Collection<String> ids) {
		if (ids.isEmpty()) {
			return Set.of();
		}
		List<String> ordered = List.copyOf(new LinkedHashSet<>(ids));
		Instant now = clock.instant();
		BulkOperations inserts = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, TimeMark.class);
		ordered.forEach(id -> inserts.insert(new TimeMark(id, now)));
		try {
			inserts.execute();
			return Set.copyOf(ordered);
		}
		catch (BulkOperationException refused) {
			Set<String> taken = new LinkedHashSet<>(ordered);
			for (BulkWriteError error : refused.getErrors()) {
				if (error.getCode() != DUPLICATE_KEY) {
					throw refused;
				}
				taken.remove(ordered.get(error.getIndex()));
			}
			return taken;
		}
	}

	/** Gives [ids] back, so the moment they stood for can come again. */
	void release(Collection<String> ids) {
		if (!ids.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("_id").in(ids)), TimeMark.class);
		}
	}

	/** Where a scan last finished, if it has. */
	Optional<Instant> watermark(String id) {
		return Optional.ofNullable(mongo.findById(id, TimeMark.class)).map(TimeMark::getAt);
	}

	void advance(String id, Instant at) {
		mongo.upsert(Query.query(Criteria.where("_id").is(id)), new Update().set("at", at), TimeMark.class);
	}

	/** Every reminder mark of a deleted account. */
	long forget(String userId) {
		return mongo.remove(Query.query(Criteria.where("_id")
						.regex("^" + Pattern.quote(REMINDER + userId + ':'))), TimeMark.class)
				.getDeletedCount();
	}
}
