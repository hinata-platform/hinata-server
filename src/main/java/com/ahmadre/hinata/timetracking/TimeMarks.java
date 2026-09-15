package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The claims behind reminders and alerts, in {@code time_reminder_marks}. See
 * {@link TimeReminderMark} for why a claim is an insert and nothing more.
 *
 * <p>Shift reminders (HIN-46) are meant to claim the same way, with a key of their own.
 */
@Component
@RequiredArgsConstructor
class TimeMarks {

	private static final String REMINDER = "reminder:";

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
		mongo.find(query, TimeReminderMark.class).forEach(mark -> taken.add(mark.getId()));
		return taken;
	}

	/** Takes [id]; false when somebody took it first, here or on another instance. */
	boolean claim(String id) {
		try {
			mongo.insert(new TimeReminderMark(id, clock.instant()));
			return true;
		}
		catch (DuplicateKeyException taken) {
			return false;
		}
	}

	/** Gives [ids] back, so the moment they stood for can come again. */
	void release(Collection<String> ids) {
		if (!ids.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("_id").in(ids)), TimeReminderMark.class);
		}
	}

	/** Where a scan last finished, if it has. */
	Optional<Instant> watermark(String id) {
		return Optional.ofNullable(mongo.findById(id, TimeReminderMark.class)).map(TimeReminderMark::getAt);
	}

	void advance(String id, Instant at) {
		mongo.upsert(Query.query(Criteria.where("_id").is(id)), new Update().set("at", at), TimeReminderMark.class);
	}

	/** Every reminder mark of a deleted account. */
	long forget(String userId) {
		return mongo.remove(Query.query(Criteria.where("_id")
						.regex("^" + Pattern.quote(REMINDER + userId + ':'))), TimeReminderMark.class)
				.getDeletedCount();
	}
}
