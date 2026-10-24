package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

/**
 * What every write on a case shares (HIN-170, HIN-171): its history entry, its revision, its 409,
 * and what a changed statement owes.
 *
 * <p>A moderator's step moves the revision ({@link #step}): claiming, deciding, confirming,
 * restoring, an appeal recorded or decided, a legal hold. A second moderator acting on what they
 * saw then gets a 409 instead of writing over it. What only adds to the history moves nothing
 * ({@link #note}), because nobody decides against it: an appeal the person files, a reveal asked
 * for or given, a suspension taking effect.
 */
final class CaseSteps {

	private CaseSteps() {
	}

	/** A moderator's step: one more in the history, and the revision every later step must match. */
	static Update step(Update update, CaseEvent event) {
		update.inc("rev", 1);
		return note(update, event);
	}

	/** One more in the history, without a new revision: for what nobody decides against. */
	static Update note(Update update, CaseEvent event) {
		update.set("updatedAt", event.at());
		update.push("history").slice(-ModerationCase.HISTORY_MAX).each(event);
		return update;
	}

	/**
	 * The follow-up job runs for the case from [now], and the case is kept [months] from then, never
	 * shorter than it already was: a {@code $max}, so nothing has to be read first.
	 */
	static Update followUp(Update update, Instant now, int months) {
		return update.set("followUpDue", now).max("retainUntil", Retention.plusMonths(now, months));
	}

	/** As {@link #followUp}, and the person responsible is told that their statement changed. */
	static Update noticeChanged(Update update, Instant now, int months) {
		return followUp(update, now, months).set("noticeChangedAt", now);
	}

	/** 409 for a step on a case that moved: decided meanwhile, or changed by somebody else. */
	static ApiException stale(MongoTemplate mongo, String id) {
		ModerationCase now = mongo.findById(id, ModerationCase.class);
		return ApiException.refusal(HttpStatus.CONFLICT, now != null && now.getStatus() == CaseStatus.DECIDED
				? "error.moderation.caseDecided" : "error.moderation.caseChanged");
	}
}
