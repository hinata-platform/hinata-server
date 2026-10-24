package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.user.User;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The response target and what a decision leaves to do (HIN-170), every fifteen minutes. Safe with
 * two instances: every mark is set by {@code findAndModify} before anything is sent, so a case is
 * reminded of, escalated or worked on once.
 *
 * <ol>
 *   <li>Open cases older than 20 hours: one reminder per moderator with the number of their cases.</li>
 *   <li>Older than 24 hours: one word to the admins, without content, so they can staff moderation.</li>
 *   <li>Decided cases of the last seven days whose removal left originals behind: deleting them
 *       again. Scrubbing copies, the statement and the reporters' message join here with HIN-171.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationFollowUpJob {

	static final Duration REMIND_AFTER = Duration.ofHours(20);
	static final Duration ESCALATE_AFTER = Duration.ofHours(24);
	static final Duration REDRIVE_WINDOW = Duration.ofDays(7);
	static final Duration REDRIVE_GRACE = Duration.ofMinutes(5);

	/** Cases handled per step and run; the next run takes the rest. */
	static final int BATCH = 200;

	private final MongoTemplate mongo;
	private final CaseAccess access;
	private final ModerationNotices notices;
	private final Quarantine quarantine;
	private final Clock clock;

	@Scheduled(cron = "0 */15 * * * *")
	public void run() {
		Instant now = clock.instant();
		remind(now);
		escalate(now);
		redrive(now);
	}

	void remind(Instant now) {
		Map<User, Integer> waiting = new LinkedHashMap<>();
		Map<String, User> byId = new LinkedHashMap<>();
		for (ModerationCase due : claim("remindedAt", now.minus(REMIND_AFTER), now)) {
			for (User moderator : access.eligibleModerators(due, CaseAccess.Purpose.SEE)) {
				User person = byId.computeIfAbsent(moderator.getId(), id -> moderator);
				waiting.merge(person, 1, Integer::sum);
			}
		}
		if (!waiting.isEmpty()) {
			notices.overdue(waiting);
		}
	}

	void escalate(Instant now) {
		List<ModerationCase> escalated = claim("escalatedAt", now.minus(ESCALATE_AFTER), now);
		if (!escalated.isEmpty()) {
			notices.adminAlert(escalated, "moderation.alert.overdue");
		}
	}

	/**
	 * The open cases older than [before] without [mark], each marked first and only then handed
	 * back: a second instance finds the mark and leaves the case alone.
	 */
	private List<ModerationCase> claim(String mark, Instant before, Instant now) {
		List<ModerationCase> claimed = new ArrayList<>();
		Query due = Query.query(Criteria.where("status").is(CaseStatus.OPEN).and("firstReportedAt").lt(before)
				.and(mark).is(null));
		for (int i = 0; i < BATCH; i++) {
			ModerationCase next = mongo.findAndModify(due, new Update().set(mark, now),
					FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
			if (next == null) {
				break;
			}
			claimed.add(next);
		}
		return claimed;
	}

	/** Originals a removal could not confirm gone, deleted again. */
	void redrive(Instant now) {
		Query pending = Query.query(Criteria.where("status").is(CaseStatus.DECIDED)
				.and("decidedAt").gte(now.minus(REDRIVE_WINDOW)).lt(now.minus(REDRIVE_GRACE))
				.and("pendingDeletes.0").exists(true)).limit(BATCH);
		for (ModerationCase decided : mongo.find(pending, ModerationCase.class)) {
			List<String> left = quarantine.deleteOriginals(decided.getPendingDeletes());
			Update update = new Update().set("pendingDeletes", left).set("updatedAt", now);
			if (left.isEmpty()) {
				update.set("removalState", RemovalState.DONE);
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(decided.getId())), update, ModerationCase.class);
			if (!left.isEmpty()) {
				log.warn("Case {} still has {} original(s) the store would not delete", decided.getId(), left.size());
			}
		}
	}
}
