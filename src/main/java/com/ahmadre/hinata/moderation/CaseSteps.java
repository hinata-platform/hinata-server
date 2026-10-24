package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;

/** What every moderator step on a case shares (HIN-170, HIN-171): its history entry, its revision, its 409. */
final class CaseSteps {

	private CaseSteps() {
	}

	/** A moderator's step: one more in the history, and the revision every later step must match. */
	static Update step(Update update, CaseEvent event) {
		update.inc("rev", 1).set("updatedAt", event.at());
		update.push("history").slice(-ModerationCase.HISTORY_MAX).each(event);
		return update;
	}

	/** 409 for a step on a case that moved: decided meanwhile, or changed by somebody else. */
	static ApiException stale(MongoTemplate mongo, String id) {
		ModerationCase now = mongo.findById(id, ModerationCase.class);
		String code = now != null && now.getStatus() == CaseStatus.DECIDED ? "decided" : "changed";
		return ApiException.conflict("error.moderation.caseChanged", Map.of("code", code));
	}
}
