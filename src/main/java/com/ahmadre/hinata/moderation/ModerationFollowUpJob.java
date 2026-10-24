package com.ahmadre.hinata.moderation;

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
 *   <li>Open cases older than {@link ResponseTimes#REMIND_AFTER}: one reminder per moderator with the
 *       number of their cases.</li>
 *   <li>Older than {@link ResponseTimes#OVERDUE_AFTER}: one word to the admins, without content, so they
 *       can staff moderation.</li>
 *   <li>Decided cases due a step for at most seven days: the removal, its originals, the copies,
 *       the statement, the reporters, a changed notice ({@link ModerationFollowUps#complete}).
 *       Each case is held for a while first, so a second instance leaves it alone.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationFollowUpJob {

	static final Duration REDRIVE_WINDOW = Duration.ofDays(7);
	static final Duration REDRIVE_GRACE = Duration.ofMinutes(5);
	/** How long a run holds a case it works on. */
	static final Duration REDRIVE_HOLD = Duration.ofMinutes(10);

	/** Cases handled per step and run; the next run takes the rest. */
	static final int BATCH = 200;

	private final MongoTemplate mongo;
	private final CaseAccess access;
	private final ModerationAlerts alerts;
	private final ModerationFollowUps followUps;
	private final Clock clock;

	@Scheduled(cron = "0 */15 * * * *")
	public void run() {
		Instant now = clock.instant();
		remind(now);
		escalate(now);
		redrive(now);
	}

	void remind(Instant now) {
		List<ModerationCase> due = claim("remindedAt", now.minus(ResponseTimes.REMIND_AFTER), now);
		if (due.isEmpty()) {
			return;
		}
		List<User> moderators = access.moderators();
		Map<User, Integer> waiting = new LinkedHashMap<>();
		for (ModerationCase moderationCase : due) {
			access.eligibleModerators(moderationCase, CaseAccess.Purpose.SEE, moderators)
					.forEach(moderator -> waiting.merge(moderator, 1, Integer::sum));
		}
		if (!waiting.isEmpty()) {
			alerts.overdue(waiting);
		}
	}

	void escalate(Instant now) {
		List<ModerationCase> escalated = claim("escalatedAt", now.minus(ResponseTimes.OVERDUE_AFTER), now);
		if (!escalated.isEmpty()) {
			alerts.adminAlert(escalated, "moderation.alert.overdue", ResponseTimes.hours(ResponseTimes.OVERDUE_AFTER));
		}
	}

	/**
	 * The open cases older than [before] without [mark], each marked first and only then handed
	 * back: a second instance finds the mark and leaves the case alone. One read finds the due
	 * ids; each is then marked by its id and comes back with what the alerts read of it.
	 */
	private List<ModerationCase> claim(String mark, Instant before, Instant now) {
		Query due = Query.query(Criteria.where("status").is(CaseStatus.OPEN).and("firstReportedAt").lt(before)
				.and(mark).is(null)).limit(BATCH);
		due.fields().include("_id");
		List<ModerationCase> claimed = new ArrayList<>();
		for (ModerationCase candidate : mongo.find(due, ModerationCase.class)) {
			Query unmarked = Query.query(Criteria.where("_id").is(candidate.getId())
					.and("status").is(CaseStatus.OPEN).and(mark).is(null));
			unmarked.fields().include("contributorIds");
			ModerationCase next = mongo.findAndModify(unmarked, new Update().set(mark, now),
					FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
			if (next != null) {
				claimed.add(next);
			}
		}
		return claimed;
	}

	/**
	 * What decided cases still owe, from five minutes after it fell due (the request does it
	 * itself first) to seven days, when the job gives up and the case shows what is missing.
	 */
	void redrive(Instant now) {
		Query due = Query.query(Criteria.where("followUpDue").gte(now.minus(REDRIVE_WINDOW))
				.lt(now.minus(REDRIVE_GRACE)).exists(true)
				.orOperator(Criteria.where("followUpUntil").is(null), Criteria.where("followUpUntil").lt(now)));
		for (int i = 0; i < BATCH; i++) {
			ModerationCase next = mongo.findAndModify(due, new Update().set("followUpUntil", now.plus(REDRIVE_HOLD)),
					FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
			if (next == null) {
				break;
			}
			try {
				followUps.complete(next.getId());
			}
			catch (RuntimeException ex) {
				log.warn("[moderation] follow-up of case {} failed", next.getId(), ex);
			}
		}
	}
}
