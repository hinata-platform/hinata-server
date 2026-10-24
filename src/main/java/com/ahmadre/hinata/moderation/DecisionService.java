package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.SuspensionService;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.moderation.CaseAccess.Guarded;
import com.ahmadre.hinata.moderation.CaseAccess.Purpose;
import com.ahmadre.hinata.moderation.CaseViews.CaseSummary;
import com.ahmadre.hinata.moderation.CaseViews.PersonRef;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deciding a case (HIN-171), and what can be done to a decision afterwards without an appeal:
 * undoing its removal, holding the case for law enforcement.
 *
 * <p>The decision is saved first, at the revision the moderator saw, and only then carried out:
 * the removal, then the copies, the statement and the reporters ({@link ModerationFollowUps}). A
 * removal the store refuses leaves the decision standing and the case {@code INCOMPLETE}; the
 * follow-up job carries it out later. The human decision is never in question, only its execution
 * is repeated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

	/** What moderation found, when it acts or calls a report unfounded: at least this long. */
	static final int FACTS_MIN = 20;

	/** Removals by one person in an hour past which the admins hear of it. */
	static final int REMOVALS_PER_HOUR = 10;

	/** How long a note that law enforcement was told holds a case. */
	static final int LEGAL_HOLD_MONTHS = 12;

	/** A legal hold without an end. */
	static final Instant INDEFINITELY = Instant.parse("9999-12-31T23:59:59Z");

	private final CaseAccess access;
	private final MongoTemplate mongo;
	private final List<ContentSurface> surfaces;
	private final ModerationFollowUps followUps;
	private final ModerationStats stats;
	private final ModerationAlerts alerts;
	private final Statements statements;
	private final AuditService audit;
	private final SettingsService settings;
	private final UserRepository users;
	private final UserWords words;
	private final ModerationAccess moderators;
	private final ModerationRules rules;
	private final SuspensionService suspensions;
	private final HouseRulesService houseRules;
	private final Restorations restorations;
	private final Clock clock;

	/** What a removal takes. */
	public record RemovalChoice(@NotNull RemovalScope scope) {
	}

	/** How long the person responsible is suspended (HIN-172). */
	public record SuspensionChoice(@NotNull Decision.SuspensionLength duration) {
	}

	/** A decision as a moderator makes it. */
	public record DecisionRequest(
			@NotNull Long rev,
			@NotNull Decision.Outcome outcome,
			@Valid RemovalChoice removal,
			Decision.Ground ground,
			ReportCategory category,
			@Size(max = 2000) String facts,
			@Size(max = 200) String ruleReference,
			@Size(max = 200) String lawReference,
			@Size(max = 64) String responsibleId,
			boolean deceptiveCommercial,
			@Size(max = 1000) String messageToAuthor,
			@Size(max = 2000) String internalNote,
			boolean lawEnforcementNotified,
			@Valid SuspensionChoice suspension) {

		/** A decision that suspends nobody. */
		public DecisionRequest(Long rev, Decision.Outcome outcome, RemovalChoice removal, Decision.Ground ground,
				ReportCategory category, String facts, String ruleReference, String lawReference, String responsibleId,
				boolean deceptiveCommercial, String messageToAuthor, String internalNote, boolean lawEnforcementNotified) {
			this(rev, outcome, removal, ground, category, facts, ruleReference, lawReference, responsibleId,
					deceptiveCommercial, messageToAuthor, internalNote, lawEnforcementNotified, null);
		}
	}

	/** A step on a case at the revision seen. */
	public record StepRequest(@NotNull Long rev) {
	}

	/** A step on a case at the revision seen, with a reason of at least twenty characters. */
	public record NoteRequest(@NotNull Long rev, @NotBlank @Size(max = 2000) String note) {
	}

	/** Undoing a removal without an appeal, at the revision seen, with a reason. */
	public record RestoreRequest(@NotNull Long rev, @NotBlank @Size(max = 2000) String note) {
	}

	/** Holding a case beyond its time: until a date, or without an end; always with a reason. */
	public record LegalHoldRequest(Instant until, boolean indefinite, @NotBlank @Size(max = 500) String reason) {
	}

	// --- deciding ----------------------------------------------------------------------------------

	/** The house rules to rest a decision on, in the signed-in moderator's language. */
	public ModerationRules.RuleSet rules() {
		return rules.current(words.localeOf(moderators.require()));
	}

	/**
	 * Decides case [id] as [request] says: 409 when the case moved since the revision seen, or
	 * somebody else holds it; a case nobody holds is taken by deciding it. A decision that suspends
	 * a person with a role waits for a second person instead (HIN-172).
	 */
	public CaseSummary decide(String id, DecisionRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		ModerationCase c = guarded.moderationCase();
		User moderator = guarded.moderator();
		if (c.getPendingDecision() != null) {
			throw ApiException.conflict("error.moderation.confirmationPending");
		}
		Instant now = clock.instant();
		Decision decision = decisionOf(c, request, moderator, now);
		User suspended = suspendedBy(decision, moderator);
		if (suspended != null && (suspended.isModerator() || suspended.isOrgAdmin())) {
			return requestConfirmation(id, request.rev(), decision, moderator, now);
		}
		Criteria free = new Criteria().andOperator(Criteria.where("pendingDecision").is(null),
				new Criteria().orOperator(Criteria.where("assigneeId").is(null),
						Criteria.where("assigneeId").is(moderator.getId())));
		return apply(id, request.rev(), free, decision, moderator, moderator, now);
	}

	/** Whom [decision] suspends, checked: 400 when nobody with an account is responsible or may be. */
	private User suspendedBy(Decision decision, User moderator) {
		if (decision.suspension() == null) {
			return null;
		}
		User person = decision.responsibleId() == null ? null : users.findById(decision.responsibleId()).orElse(null);
		if (person == null || !person.isActive()) {
			throw ApiException.badRequest("error.moderation.suspensionNotAllowed");
		}
		suspensions.requireSuspendable(person, moderator);
		return person;
	}

	/**
	 * Saves [decision] on case [id] at [rev] where [condition] holds, then carries it out: the
	 * removal, the suspension, and off the request the copies, the statement and the reporters.
	 * [decider] decided it, [actor] is who acts now, the second person for a confirmed decision.
	 */
	private CaseSummary apply(String id, long rev, Criteria condition, Decision decision, User decider, User actor,
			Instant now) {
		boolean removing = decision.outcome() == Decision.Outcome.ACTION && decision.removal() != null;
		Query open = Query.query(new Criteria().andOperator(
				Criteria.where("_id").is(id).and("rev").is(rev).and("status").is(CaseStatus.OPEN), condition));
		Update update = CaseSteps.step(new Update()
						.set("status", CaseStatus.DECIDED)
						.set("decision", decision)
						.set("decidedAt", now)
						.set("assigneeId", decider.getId())
						.set("retainUntil", Retention.afterDecision(decision, now, retentionMonths()))
						.set("followUpDue", now)
						.unset("pendingDecision"),
				new CaseEvent(now, CaseEvent.Kind.DECIDED, actor.getId(), decision.outcome().name()));
		if (decision.responsibleId() != null) {
			update.set("responsibleId", decision.responsibleId());
		}
		if (removing) {
			update.set("removalState", RemovalState.INCOMPLETE);
		}
		if (!decision.acted()) {
			// Nothing was wrong with it: moderation keeps no copy of it.
			update.unset("evidence").unset("listExcerpt");
		}
		if (Boolean.TRUE.equals(decision.lawEnforcementNotified())) {
			update.set("legalHoldUntil", Retention.plusMonths(now, LEGAL_HOLD_MONTHS));
		}
		ModerationCase decided = mongo.findAndModify(open, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (decided == null) {
			throw CaseSteps.stale(mongo, id);
		}
		audit.event(AuditAction.MODERATION_DECIDED).actor(actor).meta("case", id).log();
		if (Boolean.TRUE.equals(decision.lawEnforcementNotified())) {
			audit.event(AuditAction.MODERATION_LEGAL_HOLD_CHANGED).actor(actor).meta("case", id).log();
		}
		if (removing) {
			decided = followUps.removeFor(decided);
		}
		if (decision.suspension() != null) {
			decided = suspendFor(decided, decider, now);
		}
		// Counted once the measures are what they are: a suspension refused meanwhile is none.
		stats.decided(decided, decision, now);
		if (removing || decision.suspension() != null) {
			alertOnManyRemovals(decided, decider, now);
		}
		followUps.later(id);
		return CaseService.summaryOf(decided, personOf(decider), access.reported(decided, actor.getId()));
	}

	/**
	 * Suspends the person responsible for [c] as its decision says. A refusal now (the last
	 * moderator left meanwhile) leaves the decision standing and the case without a suspension,
	 * which the statement then does not mention.
	 */
	private ModerationCase suspendFor(ModerationCase c, User decider, Instant now) {
		Decision decision = c.getDecision();
		User person = users.findById(decision.responsibleId()).orElse(null);
		if (person == null) {
			return c;
		}
		Instant until = decision.suspension().until(now);
		try {
			suspensions.suspend(person, until, c.getId(), decider);
		}
		catch (ApiException refused) {
			log.warn("[moderation] suspension for case {} refused: {}", c.getId(), refused.getMessageKey());
			return c;
		}
		Update update = CaseSteps.note(new Update().set("suspension", new CaseSuspension(person.getId(), until, now,
				null, null)), new CaseEvent(now, CaseEvent.Kind.SUSPENDED, decider.getId(), decision.suspension().name()));
		return mongo.findAndModify(Query.query(Criteria.where("_id").is(c.getId())), update,
				FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
	}

	/**
	 * Keeps [decision], which suspends a person with a role, until a second person confirms it.
	 * The second persons hear of it, and the admins, without content.
	 */
	private CaseSummary requestConfirmation(String id, long rev, Decision decision, User moderator, Instant now) {
		Query open = Query.query(new Criteria().andOperator(
				Criteria.where("_id").is(id).and("rev").is(rev).and("status").is(CaseStatus.OPEN)
						.and("pendingDecision").is(null),
				new Criteria().orOperator(Criteria.where("assigneeId").is(null),
						Criteria.where("assigneeId").is(moderator.getId()))));
		Update update = CaseSteps.step(new Update().set("pendingDecision", decision).set("assigneeId", moderator.getId())
						.set("claimedAt", now),
				new CaseEvent(now, CaseEvent.Kind.CONFIRMATION_REQUESTED, moderator.getId(), decision.outcome().name()));
		ModerationCase pending = mongo.findAndModify(open, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (pending == null) {
			throw CaseSteps.stale(mongo, id);
		}
		alerts.confirmRequested(pending, "moderation.notice.confirmDecision");
		alerts.adminAlert(pending, "moderation.alert.suspendRole");
		return CaseService.summaryOf(pending, personOf(moderator), access.reported(pending, moderator.getId()));
	}

	/** Confirms the decision waiting on case [id], as the second pair of eyes; then it takes effect. */
	public CaseSummary confirm(String id, StepRequest request) {
		Guarded guarded = access.require(id, Purpose.SECOND_PERSON);
		Decision pending = guarded.moderationCase().getPendingDecision();
		if (pending == null) {
			throw CaseSteps.stale(mongo, id);
		}
		User confirmer = guarded.moderator();
		User decider = users.findById(pending.decidedBy()).orElse(confirmer);
		return apply(id, request.rev(), Criteria.where("pendingDecision").exists(true),
				pending.confirmedBy(confirmer.getId()), decider, confirmer, clock.instant());
	}

	/** Turns down the decision waiting on case [id], with a reason; the case is open again. */
	public CaseSummary reject(String id, NoteRequest request) {
		Guarded guarded = access.require(id, Purpose.SECOND_PERSON);
		requireFacts(request.note());
		Instant now = clock.instant();
		Query pending = Query.query(Criteria.where("_id").is(id).and("rev").is(request.rev())
				.and("pendingDecision").exists(true));
		Update update = CaseSteps.step(new Update().unset("pendingDecision").unset("assigneeId").unset("claimedAt"),
				new CaseEvent(now, CaseEvent.Kind.CONFIRMATION_REJECTED, guarded.moderator().getId(), request.note().strip()));
		ModerationCase reopened = mongo.findAndModify(pending, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (reopened == null) {
			throw CaseSteps.stale(mongo, id);
		}
		return CaseService.summaryOf(reopened, null, access.reported(reopened, guarded.moderator().getId()));
	}

	/**
	 * The statement [request] would send, worded as the person responsible will read it, without
	 * saving anything: what the moderator previews is what goes out.
	 */
	public Statement preview(String id, DecisionRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		ModerationCase c = guarded.moderationCase();
		Instant now = clock.instant();
		Decision decision = decisionOf(c, request, guarded.moderator(), now);
		if (!DecisionNotices.owesStatement(decision)) {
			throw ApiException.badRequest("error.moderation.outcomeNotAllowed");
		}
		User responsible = decision.responsibleId() == null ? null : users.findById(decision.responsibleId()).orElse(null);
		Locale locale = responsible == null ? settings.instanceLocale() : words.localeOf(responsible);
		ZoneId zone = UserZones.of(responsible, settings.get());
		return statements.of(c, decision, locale, zone, responsible != null && DecisionNotices.canSignIn(responsible), now);
	}

	/** [request] as a decision on [c], checked against what its outcome needs. */
	Decision decisionOf(ModerationCase c, DecisionRequest request, User moderator, Instant now) {
		Decision.Outcome outcome = request.outcome();
		ContentKind kind = c.getTarget() == null ? ContentKind.OTHER : c.getTarget().kind();
		boolean other = kind == ContentKind.OTHER;
		boolean acts = outcome == Decision.Outcome.ACTION || outcome == Decision.Outcome.HANDLED;
		boolean suspends = request.suspension() != null;
		if (outcome == Decision.Outcome.ACTION && other || outcome == Decision.Outcome.HANDLED && !other
				|| (request.removal() != null || suspends) && outcome != Decision.Outcome.ACTION) {
			throw ApiException.badRequest("error.moderation.outcomeNotAllowed");
		}
		if (outcome == Decision.Outcome.ACTION) {
			// An action removes, suspends, or both (HIN-172).
			if (request.removal() != null || !suspends) {
				surface(kind).requireScope(request.removal() == null ? null : request.removal().scope());
			}
		}
		if (acts && request.ground() == null) {
			throw ApiException.badRequest("error.moderation.groundRequired");
		}
		if (acts && request.category() == null) {
			throw ApiException.badRequest("error.moderation.categoryRequired");
		}
		if (outcome != Decision.Outcome.NO_VIOLATION) {
			requireFacts(request.facts());
		}
		if (outcome == Decision.Outcome.ACTION && blank(request.ground() == Decision.Ground.LAW
				? request.lawReference() : request.ruleReference())) {
			throw ApiException.badRequest("error.moderation.referenceRequired");
		}
		if (request.deceptiveCommercial() && (outcome != Decision.Outcome.ACTION
				|| request.category() != ReportCategory.SPAM)) {
			throw ApiException.badRequest("error.moderation.outcomeNotAllowed");
		}
		Set<ReportSource> sources = c.getSources() == null ? Set.of() : c.getSources();
		String responsible = outcome == Decision.Outcome.ACTION ? responsibleOf(c, request.responsibleId()) : null;
		if (suspends && responsible == null) {
			// Content from e-mail or a smart commit has nobody responsible to suspend (HIN-169).
			throw ApiException.badRequest("error.moderation.suspensionNotAllowed");
		}
		return new Decision(outcome,
				request.removal() == null ? null : request.removal().scope(),
				acts ? request.ground() : null,
				acts ? request.category() : null,
				strip(request.facts()),
				acts ? strip(request.ruleReference()) : null,
				acts ? strip(request.lawReference()) : null,
				responsible,
				request.deceptiveCommercial(),
				outcome == Decision.Outcome.ACTION ? strip(request.messageToAuthor()) : null,
				strip(request.internalNote()),
				request.lawEnforcementNotified(),
				basisOf(sources),
				sources.contains(ReportSource.AUTOMATED),
				houseRules.currentVersion(),
				moderator.getId(),
				now,
				null,
				suspends ? request.suspension().duration() : null,
				null);
	}

	/** Who gets the statement: whom the moderator chose among those who wrote it, else who wrote it last. */
	private static String responsibleOf(ModerationCase c, String chosen) {
		Set<String> contributors = c.getContributorIds() == null ? Set.of() : c.getContributorIds();
		if (!blank(chosen)) {
			if (!contributors.contains(chosen)) {
				throw ApiException.badRequest("error.moderation.responsibleNotContributor");
			}
			return chosen;
		}
		String lastEditor = c.getEvidence() == null ? null : c.getEvidence().lastEditorId();
		if (lastEditor != null && contributors.contains(lastEditor)) {
			return lastEditor;
		}
		// Content from e-mail or a smart commit has nobody responsible with an account (HIN-169).
		return contributors.stream().findFirst().orElse(null);
	}

	private static Decision.Basis basisOf(Set<ReportSource> sources) {
		if (sources.contains(ReportSource.MEMBER) || sources.contains(ReportSource.PUBLIC)) {
			return Decision.Basis.NOTICE;
		}
		return sources.contains(ReportSource.OWN_INITIATIVE) ? Decision.Basis.OWN_INITIATIVE
				: Decision.Basis.AUTOMATED_DETECTION;
	}


	/** The admins hear once when one person removes more than ten times within an hour. */
	private void alertOnManyRemovals(ModerationCase decided, User moderator, Instant now) {
		long lastHour = mongo.count(Query.query(Criteria.where("status").is(CaseStatus.DECIDED)
				.and("decidedAt").gte(now.minus(Duration.ofHours(1)))
				.and("decision.decidedBy").is(moderator.getId())
				.and("decision.outcome").is(Decision.Outcome.ACTION)), ModerationCase.class);
		if (lastHour == REMOVALS_PER_HOUR + 1) {
			alerts.adminAlert(decided, "moderation.alert.many");
		}
	}

	// --- after the decision --------------------------------------------------------------------

	/** Undoes the removal of case [id] without an appeal; refused while an appeal waits. */
	public CaseSummary restore(String id, RestoreRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		requireFacts(request.note());
		Instant now = clock.instant();
		Restorations.Undone undone = restorations.undo(id, request.rev(), moderator, request.note().strip(), now,
				Criteria.where("appeal.state").ne(Appeal.State.PENDING), SuspensionService.LiftReason.MODERATION,
				outcome -> CaseSteps.note(new Update(),
						new CaseEvent(now, CaseEvent.Kind.RESTORED, moderator.getId(), outcome.name())));
		audit.event(AuditAction.MODERATION_RESTORED).actor(moderator).meta("case", id).log();
		followUps.later(id);
		return CaseService.summaryOf(undone.moderationCase(), assigneeOf(undone.moderationCase()),
				access.reported(undone.moderationCase(), moderator.getId()));
	}


	/** Lifts the suspension case [id] decided, with a reason; whatever it removed stays removed. */
	public CaseSummary liftSuspension(String id, NoteRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		requireFacts(request.note());
		CaseSuspension running = guarded.moderationCase().getSuspension();
		if (running == null || !running.running()) {
			throw CaseSteps.stale(mongo, id);
		}
		Instant now = clock.instant();
		Query query = Query.query(Criteria.where("_id").is(id).and("rev").is(request.rev())
				.and("suspension.liftedAt").is(null).and("suspension.personId").is(running.personId()));
		Update step = CaseSteps.step(new Update(),
				new CaseEvent(now, CaseEvent.Kind.SUSPENSION_LIFTED, moderator.getId(), request.note().strip()));
		if (mongo.findAndModify(query, step, ModerationCase.class) == null) {
			throw CaseSteps.stale(mongo, id);
		}
		users.findById(running.personId())
				.ifPresent(person -> suspensions.lift(person, SuspensionService.LiftReason.MODERATION, moderator,
						request.note().strip()));
		ModerationCase after = mongo.findById(id, ModerationCase.class);
		return CaseService.summaryOf(after, assigneeOf(after), access.reported(after, moderator.getId()));
	}

	/** Holds case [id] beyond its time: never shorter than it is held already. */
	public CaseSummary legalHold(String id, LegalHoldRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		ModerationCase c = guarded.moderationCase();
		User moderator = guarded.moderator();
		Instant now = clock.instant();
		Instant until = request.indefinite() ? INDEFINITELY : request.until();
		if (until == null || !until.isAfter(now) || c.getLegalHoldUntil() != null && !until.isAfter(c.getLegalHoldUntil())) {
			throw ApiException.badRequest("error.moderation.legalHoldUntil");
		}
		Update update = CaseSteps.step(new Update().set("legalHoldUntil", until),
				new CaseEvent(now, CaseEvent.Kind.HELD, moderator.getId(), request.reason().strip()));
		ModerationCase held = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), update,
				FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
		if (held == null) {
			throw ApiException.notFound("moderationCase");
		}
		audit.event(AuditAction.MODERATION_LEGAL_HOLD_CHANGED).actor(moderator).meta("case", id).log();
		return CaseService.summaryOf(held, assigneeOf(held), access.reported(held, moderator.getId()));
	}

	// --- helpers ---------------------------------------------------------------------------------

	static void requireFacts(String facts) {
		if (facts == null || facts.strip().length() < FACTS_MIN) {
			throw ApiException.badRequest("error.moderation.factsRequired", FACTS_MIN);
		}
	}

	private int retentionMonths() {
		return Retention.months(settings);
	}

	private PersonRef assigneeOf(ModerationCase c) {
		return c.getAssigneeId() == null ? null
				: users.findById(c.getAssigneeId()).map(DecisionService::personOf).orElse(null);
	}

	private static PersonRef personOf(User user) {
		return new PersonRef(user.getId(), user.getDisplayName(), user.getAvatarUrl());
	}

	private ContentSurface surface(ContentKind kind) {
		return surfaces.stream().filter(surface -> surface.kind() == kind).findFirst()
				.orElseThrow(() -> ApiException.badRequest("error.moderation.scopeNotAllowed"));
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String strip(String value) {
		return blank(value) ? null : value.strip();
	}
}
