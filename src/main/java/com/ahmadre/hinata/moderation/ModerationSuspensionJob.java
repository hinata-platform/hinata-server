package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Date;
import java.util.List;

/**
 * Ends suspensions whose time ran out (HIN-172), every five minutes. Safe with two instances:
 * {@link SuspensionService#lift} ends each one once, and the other finds nothing to end.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationSuspensionJob {

	/** Accounts ended per run at most; the next run takes the rest. */
	static final int BATCH = 200;

	private final MongoTemplate mongo;
	private final SuspensionService suspensions;
	private final Clock clock;

	@Scheduled(cron = "0 */5 * * * *")
	public void run() {
		// Off suspension_until: $type repeats its partial filter word for word, so the planner takes it
		// and suspensions until further notice are never read.
		Query due = new BasicQuery(new Document("suspension.until",
				new Document("$lte", Date.from(clock.instant())).append("$type", "date"))).limit(BATCH);
		List<User> ended = mongo.find(due, User.class);
		int lifted = 0;
		for (User person : ended) {
			if (suspensions.lift(person, SuspensionService.LiftReason.EXPIRED, null, null)) {
				lifted++;
			}
		}
		if (lifted > 0) {
			log.info("[moderation] {} suspension(s) ran out", lifted);
		}
	}
}
