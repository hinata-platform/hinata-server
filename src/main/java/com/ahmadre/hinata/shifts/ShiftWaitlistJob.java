package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The waiting lists' hourly round (HIN-162), 35 minutes past every hour.
 *
 * <p>First the places whose confirmation deadline passed by the run's planned time {@code t} go
 * back: each with a compare-and-set that only matches a place still waiting for its yes, so a
 * confirmation that commits first wins and two instances take each place back once; the next
 * person then moves up after that commit. Then the net under moving up after a commit: every shift
 * somebody waits for that starts after the cutoff is offered to {@link ShiftWaitlist#promote},
 * which does nothing where no place is free. Places that opened while the move-up after the commit
 * failed, or sign-up that was opened again, are caught here within the hour.
 *
 * <p>With the module off a run does nothing at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftWaitlistJob {

	/** Far above an hour's worth of deadlines on any real instance; the next run takes the rest. */
	static final int BATCH_MAX = 5_000;

	/** Past the planned hour, so the run does not meet the reminder run at :05. */
	static final Duration PAST_THE_HOUR = Duration.ofMinutes(35);

	private final MongoTemplate mongo;
	private final ShiftSettings settings;
	private final ShiftWaitlist waitlist;
	private final Clock clock;

	/** What one run did. */
	public record Result(int expired, int promoted) {
	}

	@Scheduled(cron = "0 35 * * * *")
	public void hourly() {
		try {
			Result result = run(planned(clock.instant()));
			if (result.expired() > 0 || result.promoted() > 0) {
				log.info("[shifts] waiting lists: {} deadline(s) passed, {} moved up", result.expired(),
						result.promoted());
			}
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] waiting list run failed: {}", failure.toString(), failure);
		}
	}

	/** The run's planned time: 35 minutes past the hour, the last one that has come. */
	static Instant planned(Instant now) {
		Instant planned = now.truncatedTo(ChronoUnit.HOURS).plus(PAST_THE_HOUR);
		return planned.isAfter(now) ? planned.minus(Duration.ofHours(1)) : planned;
	}

	/** One pass for the run planned at [t]. */
	public Result run(Instant t) {
		if (!settings.enabled()) {
			return new Result(0, 0);
		}
		int expired = 0;
		// The partial filter of confirm_due, so the read walks only places waiting for a yes.
		Query due = Query.query(Criteria.where("status").is(ShiftAssignment.Status.ASSIGNED)
				.and("confirmBy").lte(t)).limit(BATCH_MAX);
		due.fields().include("_id");
		for (ShiftAssignment place : mongo.find(due, ShiftAssignment.class)) {
			try {
				if (waitlist.expire(place.getId(), t)) {
					expired++;
				}
			}
			catch (RuntimeException failure) {
				log.warn("[shifts] could not take back place {}: {}", place.getId(), failure.toString());
			}
		}
		int promoted = 0;
		for (String shiftId : waitedFor()) {
			try {
				promoted += waitlist.promote(shiftId);
			}
			catch (RuntimeException failure) {
				log.warn("[shifts] could not move up on shift {}: {}", shiftId, failure.toString());
			}
		}
		return new Result(expired, promoted);
	}

	/**
	 * The shifts somebody waits for that start after the cutoff. Waiting entries are few (at most
	 * fifty a shift, gone a day after it ended), so one grouping over them is cheap.
	 */
	private List<String> waitedFor() {
		Instant earliest = clock.instant().plus(waitlist.cutoff());
		Aggregation shifts = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("status").is(ShiftAssignment.Status.WAITING)
						.and("startsAt").gte(earliest).and("cancelled").is(false)),
				Aggregation.group("shiftId"),
				Aggregation.limit(BATCH_MAX));
		return mongo.aggregate(shifts, mongo.getCollectionName(ShiftAssignment.class), Document.class)
				.getMappedResults().stream().map(row -> String.valueOf(row.get("_id"))).toList();
	}
}
