package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.config.HinataProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Deletes assignments whose shift ended more than {@code hinata.shifts.retention-months} ago
 * (default twelve), once a night at 03:55.
 *
 * <p>An assignment is a record of who worked when, which is personal data and in a company
 * co-determined (§ 87 BetrVG): it is kept as long as a plan needs looking back at and then goes.
 * The shifts' counters are left as they were; a past shift takes nobody new, and its count is what
 * the plan showed. It runs whether or not the module is on today, because the data it deletes is
 * held either way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftRetentionJob {

	private final MongoTemplate mongo;
	private final HinataProperties properties;
	private final Clock clock;

	@Scheduled(cron = "0 55 3 * * *")
	public void nightly() {
		try {
			long deleted = run();
			if (deleted > 0) {
				log.info("[shifts] retention deleted {} assignment(s)", deleted);
			}
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] retention run failed: {}", failure.toString(), failure);
		}
	}

	/** One pass; returns how many assignments were deleted. */
	public long run() {
		int months = Math.max(1, properties.getShifts().getRetentionMonths());
		Instant cutoff = clock.instant().atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
		// startsAt bounds the walk on the start_end index; endsAt decides.
		return mongo.remove(Query.query(Criteria.where("startsAt").lt(cutoff).and("endsAt").lt(cutoff)),
				ShiftAssignment.class).getDeletedCount();
	}
}
