package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Reminds people of a shift about a day before it starts (HIN-46), five minutes past every hour.
 *
 * <p>The window hangs on the run's planned hour {@code t}, never on the moment it happens to run:
 * shifts starting in {@code [t+22h, t+24h)}. Two hours wide, so a run that is late or missed is
 * covered by the next one; every assignment in it is then claimed with a conditional update that
 * only matches while {@code reminderSentAt} is unset, and only a claim that matched sends. Two
 * instances, a restart or two overlapping windows therefore remind once. An assignment made less
 * than 22 hours before its start is never in a later window and gets no reminder, which is the
 * intent: whoever just signed up knows. Moving a shift or its event more than a day ahead clears
 * the mark (HIN-43), so the new time is reminded again.
 *
 * <p>The read walks the partial index {@code reminder_due} (only assignments still owed a
 * reminder, not cancelled, holding a place), so the query repeats its filter exactly. With the
 * module off a run does nothing at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftReminderJob {

	/** The window, measured from the run's planned hour. */
	static final Duration FROM = Duration.ofHours(22);
	static final Duration UNTIL = Duration.ofHours(24);

	/** Far above an hour's worth of shift starts on any real instance; the next run takes the rest. */
	static final int BATCH_MAX = 5_000;

	private final MongoTemplate mongo;
	private final ShiftSettings settings;
	private final ShiftDelivery delivery;
	private final Clock clock;

	@Scheduled(cron = "0 5 * * * *")
	public void hourly() {
		try {
			int sent = run(clock.instant().truncatedTo(ChronoUnit.HOURS));
			if (sent > 0) {
				log.info("[shifts] reminded {} assignment(s)", sent);
			}
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] reminder run failed: {}", failure.toString(), failure);
		}
	}

	/** One pass for the run planned at [plannedHour]; returns how many reminders it claimed. */
	public int run(Instant plannedHour) {
		if (!settings.enabled()) {
			return 0;
		}
		Query due = Query.query(owed().and("startsAt").gte(plannedHour.plus(FROM)).lt(plannedHour.plus(UNTIL)))
				.limit(BATCH_MAX);
		due.fields().include("_id");
		Instant now = clock.instant();
		List<ShiftAssignment> claimed = new ArrayList<>();
		for (ShiftAssignment candidate : mongo.find(due, ShiftAssignment.class)) {
			ShiftAssignment mine = mongo.findAndModify(Query.query(owed().and("_id").is(candidate.getId())),
					new Update().set("reminderSentAt", now), FindAndModifyOptions.options().returnNew(true),
					ShiftAssignment.class);
			if (mine != null) {
				claimed.add(mine);
			}
		}
		delivery.remind(claimed);
		return claimed.size();
	}

	/** The partial filter of {@code reminder_due}, word for word, so the planner can use it. */
	private static Criteria owed() {
		return Criteria.where("reminderSentAt").is(null).and("cancelled").is(false)
				.and("status").in(ShiftAssignment.Status.ACTIVE);
	}
}
