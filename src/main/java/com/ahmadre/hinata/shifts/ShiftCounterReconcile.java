package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a drifted {@code Shift.assignedCount} right once a night (03:50).
 *
 * <p>The counter only moves by one inside the assignment transactions, so it should never drift;
 * this is the net under that. Per shift it counts the active assignments and, when the number
 * differs, writes it with a compare-and-set on the counter and the {@code updatedAt} it read: an
 * assignment that commits in between changes both and the correction is skipped rather than
 * overwriting it. Shifts touched in the last five minutes are left alone for the same reason.
 * Every correction is logged.
 *
 * <p>Only shifts from a week back onwards: the retention run deletes old assignments and leaves
 * the counters of past shifts as they were, so correcting those would count the deleted people
 * out of history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftCounterReconcile {

	static final Duration QUIET = Duration.ofMinutes(5);
	static final Duration LOOK_BACK = Duration.ofDays(7);
	static final int BATCH = 500;

	private final MongoTemplate mongo;
	private final ShiftSettings settings;
	private final Clock clock;

	@Scheduled(cron = "0 50 3 * * *")
	public void nightly() {
		if (!settings.enabled()) {
			return;
		}
		try {
			int corrected = run();
			if (corrected > 0) {
				log.warn("[shifts] counter reconciliation corrected {} shift(s)", corrected);
			}
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] counter reconciliation failed: {}", failure.toString(), failure);
		}
	}

	/**
	 * One keyset page from ({@code cursor}, {@code lastId}) on, in {@code (startsAt, _id)} order.
	 * The range starts at the cursor and the tie with it is a filter on top, so the whole page is
	 * one walk along {@code live_start_id}, sorted by the index and never in memory.
	 */
	static Query page(Instant cursor, String lastId) {
		Criteria live = Criteria.where("deleted").is(false).and("startsAt").gte(cursor);
		Criteria criteria = lastId == null ? live
				: new Criteria().andOperator(live, new Criteria().orOperator(Criteria.where("startsAt").gt(cursor),
						Criteria.where("_id").gt(lastId)));
		Query page = Query.query(criteria).with(Sort.by("startsAt", "_id")).limit(BATCH).withHint("live_start_id");
		page.fields().include("assignedCount", "updatedAt", "startsAt");
		return page;
	}

	/** One pass; returns how many counters were corrected. */
	public int run() {
		Instant now = clock.instant();
		Instant quietBefore = now.minus(QUIET);
		int corrected = 0;
		Instant cursor = now.minus(LOOK_BACK);
		String lastId = null;
		while (true) {
			List<Shift> shifts = mongo.find(page(cursor, lastId), Shift.class);
			if (shifts.isEmpty()) {
				return corrected;
			}
			Map<String, Integer> counts = activeCounts(shifts.stream().map(Shift::getId).toList());
			for (Shift shift : shifts) {
				if (shift.getUpdatedAt() != null && shift.getUpdatedAt().isAfter(quietBefore)) {
					continue;
				}
				int actual = counts.getOrDefault(shift.getId(), 0);
				if (actual == shift.assigned()) {
					continue;
				}
				Criteria unchanged = Criteria.where("_id").is(shift.getId()).and("assignedCount").is(shift.getAssignedCount());
				unchanged = shift.getUpdatedAt() == null ? unchanged.and("updatedAt").exists(false)
						: unchanged.and("updatedAt").is(shift.getUpdatedAt());
				long written = mongo.updateFirst(Query.query(unchanged),
						new Update().set("assignedCount", actual).set("updatedAt", now), Shift.class).getModifiedCount();
				if (written > 0) {
					corrected++;
					log.warn("[shifts] shift {} counted {} but holds {} active assignment(s); corrected",
							shift.getId(), shift.getAssignedCount(), actual);
				}
			}
			Shift last = shifts.getLast();
			cursor = last.getStartsAt();
			lastId = last.getId();
			if (shifts.size() < BATCH) {
				return corrected;
			}
		}
	}

	private Map<String, Integer> activeCounts(List<String> shiftIds) {
		Aggregation count = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("shiftId").in(shiftIds).and("status").in(ShiftAssignment.Status.ACTIVE)),
				Aggregation.group("shiftId").count().as("n"));
		Map<String, Integer> out = new HashMap<>();
		for (Document row : mongo.aggregate(count, ShiftAssignment.class, Document.class)) {
			out.put(String.valueOf(row.get("_id")), ((Number) row.get("n")).intValue());
		}
		return out;
	}
}
