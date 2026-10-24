package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.admin.SuspensionService.LiftReason;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * What moderation does when a suspension ends, whichever way it ended (HIN-172): the case notes
 * it, keeps itself for its time after the end, and the person hears on the notice's own path.
 * When an administrator ended it, the moderators hear of it too.
 */
@Component
@RequiredArgsConstructor
class SuspensionEnds {

	private final MongoTemplate mongo;
	private final ModerationFollowUps followUps;
	private final ModerationAlerts alerts;
	private final DecisionService decisions;
	private final Clock clock;

	@EventListener
	public void onLifted(SuspensionService.SuspensionLiftedEvent event) {
		if (event.caseId() == null) {
			return;
		}
		Instant now = clock.instant();
		Update update = new Update()
				.set("suspension.liftedAt", now)
				.set("suspension.liftedHow", event.reason())
				.max("retainUntil", Retention.plusMonths(now, decisions.retentionMonths()))
				.set("followUpDue", now)
				.set("updatedAt", now);
		// A deactivated account stays out: there is nothing new to tell its person.
		if (event.reason() != LiftReason.DEACTIVATED) {
			update.set("noticeChangedAt", now);
		}
		ModerationCase ended = mongo.findAndModify(Query.query(Criteria.where("_id").is(event.caseId())
						.and("suspension.liftedAt").is(null)), update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (ended == null) {
			return;
		}
		if (event.reason() == LiftReason.ADMIN || event.reason() == LiftReason.DEACTIVATED) {
			alerts.teamNote(ended, "moderation.notice.liftedByAdmin");
		}
		followUps.later(ended.getId());
	}
}
