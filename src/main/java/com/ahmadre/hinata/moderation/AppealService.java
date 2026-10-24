package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.moderation.CaseAccess.Guarded;
import com.ahmadre.hinata.moderation.CaseAccess.Purpose;
import com.ahmadre.hinata.moderation.CaseViews.CaseSummary;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The notice of the person responsible and their appeal (HIN-171, DSA Art. 17 and 20): reading
 * the notice, appealing in the app or by a reply that moderation enters, and somebody other than
 * who decided deciding the appeal.
 */
@Service
@RequiredArgsConstructor
public class AppealService {

	private final CaseAccess access;
	private final MongoTemplate mongo;
	private final Statements statements;
	private final Restorations restorations;
	private final ModerationFollowUps followUps;
	private final ModerationAlerts alerts;
	private final ModerationStats stats;
	private final AuditService audit;
	private final SettingsService settings;
	private final UserWords words;
	private final Clock clock;

	/** An appeal in the person's own words. */
	public record AppealRequest(@NotBlank @Size(max = 2000) String text) {
	}

	/** An appeal that came by a reply to the mail, entered by moderation as it was received. */
	public record RecordedAppealRequest(@NotBlank @Size(max = 2000) String text, @NotNull @PastOrPresent Instant receivedAt) {
	}

	/** How an appeal is decided, at the revision seen, with a reason of at least twenty characters. */
	public record AppealDecisionRequest(@NotNull Long rev, @NotNull Result result, @NotBlank @Size(max = 2000) String note) {

		public enum Result {
			UPHOLD,
			REVERSE
		}
	}

	// --- the person's side -----------------------------------------------------------------------

	/** The statement of reasons of case [caseId] for [person]: 404 for anybody but the person responsible. */
	public Statement statement(String caseId, User person) {
		ModerationCase c = caseOfStatement(caseId, person);
		return statements.of(c, c.getDecision(), words.localeOf(person), UserZones.of(person, settings.get()),
				DecisionNotices.canSignIn(person), clock.instant());
	}

	/** [person] appealing the decision of case [caseId], once, within six months. */
	public Statement appeal(String caseId, User person, AppealRequest request) {
		ModerationCase c = caseOfStatement(caseId, person);
		Instant now = clock.instant();
		file(c, request.text().strip(), now, Appeal.Via.APP,
				CaseSteps.note(new Update(), new CaseEvent(now, CaseEvent.Kind.APPEALED, null, Appeal.Via.APP.name())));
		return statement(caseId, person);
	}

	/** Case [caseId] when [person] is the one responsible its statement is for; 404 otherwise. */
	private ModerationCase caseOfStatement(String caseId, User person) {
		ModerationCase c = caseId == null ? null : mongo.findById(caseId, ModerationCase.class);
		if (c == null || !DecisionNotices.owesStatement(c) || !person.getId().equals(c.getDecision().responsibleId())) {
			throw ApiException.notFound("moderationNotice");
		}
		return c;
	}

	// --- moderation's side -----------------------------------------------------------------------

	/**
	 * Enters an appeal that came by a reply to the mail. Entering is recording, not deciding:
	 * anybody who may see the case may do it, who decided it included.
	 */
	public CaseSummary record(String id, RecordedAppealRequest request) {
		Guarded guarded = access.require(id, Purpose.SEE);
		ModerationCase c = guarded.moderationCase();
		User moderator = guarded.moderator();
		if (!DecisionNotices.owesStatement(c)) {
			throw ApiException.badRequest("error.moderation.outcomeNotAllowed");
		}
		Instant now = clock.instant();
		ModerationCase filed = file(c, request.text().strip(), request.receivedAt(), Appeal.Via.REPLY,
				CaseSteps.step(new Update(), new CaseEvent(now, CaseEvent.Kind.APPEALED, moderator.getId(),
						Appeal.Via.REPLY.name())));
		audit.event(AuditAction.MODERATION_APPEAL_RECORDED).actor(moderator).meta("case", id).log();
		return CaseService.summaryOf(filed, null, access.reported(filed, moderator.getId()));
	}

	/**
	 * Files the appeal on [c] as of [filedAt]: refused once one exists, after six months and once
	 * the removal was undone. The moderators who may review it hear of it; with none, the admins.
	 */
	private ModerationCase file(ModerationCase c, String text, Instant filedAt, Appeal.Via via, Update step) {
		Decision decision = c.getDecision();
		if (c.getAppeal() != null) {
			throw ApiException.conflict("error.moderation.appealExists");
		}
		if (c.getRestoration() != null || filedAt.isAfter(Retention.appealUntil(decision.decidedAt()))) {
			throw ApiException.conflict("error.moderation.appealClosed");
		}
		Query open = Query.query(Criteria.where("_id").is(c.getId()).and("status").is(CaseStatus.DECIDED)
				.and("appeal").is(null).and("restoration").is(null));
		Update update = step
				.set("appeal", Appeal.filed(text, filedAt, via))
				.max("retainUntil", Retention.plusMonths(filedAt, Retention.months(settings)));
		ModerationCase filed = mongo.findAndModify(open, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (filed == null) {
			throw ApiException.conflict("error.moderation.appealExists");
		}
		stats.appealFiled(decision);
		alerts.appealFiled(filed);
		return filed;
	}

	/**
	 * Decides the appeal on case [id]: by somebody other than who decided the case. Reversing puts
	 * the content back; either way the person hears of it on the notice's own path.
	 */
	public CaseSummary decide(String id, AppealDecisionRequest request) {
		Guarded guarded = access.require(id, Purpose.REVIEW_APPEAL);
		User moderator = guarded.moderator();
		DecisionService.requireFacts(request.note());
		String note = request.note().strip();
		Instant now = clock.instant();
		boolean reverse = request.result() == AppealDecisionRequest.Result.REVERSE;
		ModerationCase decided;
		if (reverse) {
			// The restoring claim moved the revision already.
			decided = restorations.undo(id, request.rev(), moderator, note, now,
					Criteria.where("appeal.state").is(Appeal.State.PENDING), SuspensionService.LiftReason.APPEAL,
					outcome -> CaseSteps.note(appealDecided(Appeal.State.REVERSED, moderator, note, now, outcome),
							appealEvent(Appeal.State.REVERSED, moderator, now))).moderationCase();
		}
		else {
			Query pending = Query.query(Criteria.where("_id").is(id).and("rev").is(request.rev())
					.and("status").is(CaseStatus.DECIDED).and("appeal.state").is(Appeal.State.PENDING));
			Update update = CaseSteps.noticeChanged(CaseSteps.step(appealDecided(Appeal.State.UPHELD, moderator, note,
					now, null), appealEvent(Appeal.State.UPHELD, moderator, now)), now, Retention.months(settings));
			decided = mongo.findAndModify(pending, update, FindAndModifyOptions.options().returnNew(true),
					ModerationCase.class);
			if (decided == null) {
				throw CaseSteps.stale(mongo, id);
			}
		}
		stats.appealDecided(guarded.moderationCase().getDecision(), guarded.moderationCase().getAppeal(),
				request.result(), now);
		audit.event(AuditAction.MODERATION_APPEAL_DECIDED).actor(moderator).meta("case", id).log();
		followUps.later(id);
		return CaseService.summaryOf(decided, null, access.reported(decided, moderator.getId()));
	}

	private static Update appealDecided(Appeal.State state, User moderator, String note, Instant now,
			RestoreOutcome restored) {
		Update update = new Update()
				.set("appeal.state", state)
				.set("appeal.decidedBy", moderator.getId())
				.set("appeal.decidedAt", now)
				.set("appeal.note", note);
		if (restored != null) {
			update.set("appeal.restored", restored);
		}
		return update;
	}

	private static CaseEvent appealEvent(Appeal.State state, User moderator, Instant now) {
		return new CaseEvent(now, CaseEvent.Kind.APPEAL_DECIDED, moderator.getId(), state.name());
	}
}
