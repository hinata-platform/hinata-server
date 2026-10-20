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
import java.time.Duration;
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
 * held either way. Waiting-list entries that never became a place go a day after their shift
 * ended (HIN-162).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftRetentionJob {

	/** How long a waiting entry is kept after its shift ended (HIN-162). */
	static final Duration WAITLIST_KEPT = Duration.ofDays(1);

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
		Instant now = clock.instant();
		int months = Math.max(1, properties.getShifts().getRetentionMonths());
		Instant cutoff = now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
		// startsAt bounds the walk on the start_end index; endsAt decides.
		long deleted = mongo.remove(Query.query(Criteria.where("startsAt").lt(cutoff).and("endsAt").lt(cutoff)),
				ShiftAssignment.class).getDeletedCount();
		// HIN-162: a waiting entry that never became a place records no work. It goes a day after its
		// shift ended, still waiting or released from the list, as long as the person never moved up.
		Instant waitCutoff = now.minus(WAITLIST_KEPT);
		Criteria waitlisted = new Criteria().orOperator(
				Criteria.where("status").is(ShiftAssignment.Status.WAITING),
				Criteria.where("status").is(ShiftAssignment.Status.RELEASED).and("waitlistedAt").exists(true)
						.and("promotedAt").exists(false));
		deleted += mongo.remove(Query.query(new Criteria().andOperator(
				Criteria.where("startsAt").lt(waitCutoff).and("endsAt").lt(waitCutoff), waitlisted)),
				ShiftAssignment.class).getDeletedCount();
		return deleted;
	}
}
