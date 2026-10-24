package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Function;

/**
 * Undoing what a decision did (HIN-171, HIN-172): the content back, the suspension lifted, the
 * reports no longer folded away. For a restore of moderation's own and for an appeal that
 * reverses the decision alike, so both take the same steps in the same order.
 */
@Component
@RequiredArgsConstructor
class Restorations {

	private final MongoTemplate mongo;
	private final ModerationFollowUps followUps;
	private final UserRepository users;
	private final SuspensionService suspensions;
	private final SettingsService settings;

	/** A removal undone, and what putting the content back came to. */
	record Undone(ModerationCase moderationCase, RestoreOutcome outcome) {
	}

	/**
	 * Undoes the removal of case [id] at [rev] when [condition] holds. The case is taken with a
	 * restoration first, so a second click finds it taken; then the content goes back. When the
	 * store will not give the files back, the restoration goes again and the error reaches whoever
	 * asked, who can try once more. [after] adds the caller's own fields to the final write.
	 */
	Undone undo(String id, long rev, User moderator, String note, Instant now, Criteria condition,
			SuspensionService.LiftReason liftReason, Function<RestoreOutcome, Update> after) {
		Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(id).and("rev").is(rev)
				.and("status").is(CaseStatus.DECIDED).and("decision.outcome").is(Decision.Outcome.ACTION)
				.and("restoration").is(null), new Criteria().orOperator(Criteria.where("decision.removal").exists(true),
						Criteria.where("decision.suspension").exists(true)), condition));
		Update claim = new Update().set("restoration", new Restoration(now, moderator.getId(), note, null))
				.inc("rev", 1).set("updatedAt", now);
		ModerationCase held = mongo.findAndModify(query, claim, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (held == null) {
			throw CaseSteps.stale(mongo, id);
		}
		RestoreOutcome outcome;
		try {
			// A decision that only suspended has no content to put back.
			outcome = held.getDecision().removal() == null ? RestoreOutcome.RESTORED : followUps.restoreFor(held);
		}
		catch (RuntimeException failed) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("restoration"),
					ModerationCase.class);
			throw failed;
		}
		Update done = CaseSteps.noticeChanged(after.apply(outcome).set("restoration.outcome", outcome), now,
				Retention.months(settings));
		ModerationCase undone = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), done,
				FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
		// The comment is back: what its reporters fold away is theirs to see again (HIN-173).
		mongo.updateMulti(Query.query(Criteria.where("caseId").is(id)), new Update().set("removed", false),
				ContentReport.class);
		if (undone.getSuspension() != null && undone.getSuspension().running()) {
			users.findById(undone.getSuspension().personId())
					.ifPresent(person -> suspensions.lift(person, liftReason, moderator, note));
			undone = mongo.findById(id, ModerationCase.class);
		}
		return new Undone(undone, outcome);
	}
}
