package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.moderation.CaseAccess.Guarded;
import com.ahmadre.hinata.moderation.CaseAccess.Purpose;
import com.ahmadre.hinata.moderation.CaseViews.CaseSummary;
import com.ahmadre.hinata.moderation.CaseViews.PersonRef;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserZones;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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
import java.util.function.Function;

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

	/** Hours to a decision are counted one by one up to a month; longer ones in one bucket. */
	static final int HOURS_COUNTED = 720;

	private final CaseAccess access;
	private final MongoTemplate mongo;
	private final List<ContentSurface> surfaces;
	private final ModerationFollowUps followUps;
	private final ModerationStats stats;
	private final ModerationNotices notices;
	private final Statements statements;
	private final AuditService audit;
	private final SettingsService settings;
	private final UserRepository users;
	private final UserWords words;
	private final ModerationAccess moderators;
	private final ModerationRules rules;
	private final Clock clock;

	/** What a removal takes; HIN-172 adds a suspension beside it. */
	public record RemovalChoice(@NotNull RemovalScope scope) {
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
			boolean lawEnforcementNotified) {
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
	 * somebody else holds it; a case nobody holds is taken by deciding it.
	 */
	public CaseSummary decide(String id, DecisionRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		Instant now = clock.instant();
		Decision decision = decisionOf(guarded.moderationCase(), request, moderator, now);
		boolean removing = decision.outcome() == Decision.Outcome.ACTION && decision.removal() != null;
		Query open = Query.query(new Criteria().andOperator(
				Criteria.where("_id").is(id).and("rev").is(request.rev()).and("status").is(CaseStatus.OPEN),
				new Criteria().orOperator(Criteria.where("assigneeId").is(null),
						Criteria.where("assigneeId").is(moderator.getId()))));
		Update update = CaseSteps.step(new Update()
						.set("status", CaseStatus.DECIDED)
						.set("decision", decision)
						.set("decidedAt", now)
						.set("assigneeId", moderator.getId())
						.set("retainUntil", Retention.afterDecision(decision, now, retentionMonths()))
						.set("followUpDue", now),
				new CaseEvent(now, CaseEvent.Kind.DECIDED, moderator.getId(), decision.outcome().name()));
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
		count(decided, decision, now);
		audit.event(AuditAction.MODERATION_DECIDED).actor(moderator).meta("case", id).log();
		if (Boolean.TRUE.equals(decision.lawEnforcementNotified())) {
			audit.event(AuditAction.MODERATION_LEGAL_HOLD_CHANGED).actor(moderator).meta("case", id).log();
		}
		if (removing) {
			decided = followUps.removeFor(decided);
			alertOnManyRemovals(decided, moderator, now);
		}
		followUps.later(id);
		return CaseService.summaryOf(decided, personOf(moderator), access.reported(decided, moderator.getId()));
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
		if (outcome == Decision.Outcome.ACTION && other || outcome == Decision.Outcome.HANDLED && !other
				|| request.removal() != null && outcome != Decision.Outcome.ACTION) {
			throw ApiException.badRequest("error.moderation.outcomeNotAllowed");
		}
		if (outcome == Decision.Outcome.ACTION) {
			surface(kind).requireScope(request.removal() == null ? null : request.removal().scope());
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
		return new Decision(outcome,
				outcome == Decision.Outcome.ACTION ? request.removal().scope() : null,
				acts ? request.ground() : null,
				acts ? request.category() : null,
				strip(request.facts()),
				acts ? strip(request.ruleReference()) : null,
				acts ? strip(request.lawReference()) : null,
				outcome == Decision.Outcome.ACTION ? responsibleOf(c, request.responsibleId()) : null,
				request.deceptiveCommercial(),
				outcome == Decision.Outcome.ACTION ? strip(request.messageToAuthor()) : null,
				strip(request.internalNote()),
				request.lawEnforcementNotified(),
				basisOf(sources),
				sources.contains(ReportSource.AUTOMATED),
				null,
				moderator.getId(),
				now,
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

	private void count(ModerationCase c, Decision decision, Instant now) {
		stats.count(StatMetric.DECISION_OUTCOME, decision.outcome().name());
		if (decision.ground() != null) {
			stats.count(StatMetric.DECISION_GROUND, decision.ground().name());
		}
		if (decision.category() != null) {
			stats.count(StatMetric.DECISION_CATEGORY, decision.category().name());
		}
		stats.count(StatMetric.DECISION_AUTOMATED_DETECTION, String.valueOf(Boolean.TRUE.equals(decision.automatedDetection())));
		stats.count(StatMetric.HOURS_TO_DECISION, hoursBucket(c.getFirstReportedAt(), now));
	}

	/** The bucket of the hours from the first report to the decision: {@code 0} to {@code 720}, then {@code overflow}. */
	static String hoursBucket(Instant firstReportedAt, Instant decidedAt) {
		long hours = firstReportedAt == null ? 0 : Math.max(Duration.between(firstReportedAt, decidedAt).toHours(), 0);
		return hours > HOURS_COUNTED ? "overflow" : String.valueOf(hours);
	}

	/** The admins hear once when one person removes more than ten times within an hour. */
	private void alertOnManyRemovals(ModerationCase decided, User moderator, Instant now) {
		long lastHour = mongo.count(Query.query(Criteria.where("status").is(CaseStatus.DECIDED)
				.and("decidedAt").gte(now.minus(Duration.ofHours(1)))
				.and("decision.decidedBy").is(moderator.getId())
				.and("decision.outcome").is(Decision.Outcome.ACTION)), ModerationCase.class);
		if (lastHour == REMOVALS_PER_HOUR + 1) {
			notices.adminAlert(decided, "moderation.alert.many");
		}
	}

	// --- after the decision --------------------------------------------------------------------

	/** Undoes the removal of case [id] without an appeal; refused while an appeal waits. */
	public CaseSummary restore(String id, RestoreRequest request) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		requireFacts(request.note());
		Instant now = clock.instant();
		Undone undone = undo(id, request.rev(), moderator, request.note().strip(), now,
				Criteria.where("appeal.state").ne(Appeal.State.PENDING),
				outcome -> new Update().push("history").slice(-ModerationCase.HISTORY_MAX)
						.each(new CaseEvent(now, CaseEvent.Kind.RESTORED, moderator.getId(), outcome.name())));
		audit.event(AuditAction.MODERATION_RESTORED).actor(moderator).meta("case", id).log();
		followUps.later(id);
		return CaseService.summaryOf(undone.moderationCase(), assigneeOf(undone.moderationCase()),
				access.reported(undone.moderationCase(), moderator.getId()));
	}

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
			Function<RestoreOutcome, Update> after) {
		Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(id).and("rev").is(rev)
				.and("status").is(CaseStatus.DECIDED).and("decision.outcome").is(Decision.Outcome.ACTION)
				.and("decision.removal").exists(true).and("restoration").is(null), condition));
		Update claim = new Update().set("restoration", new Restoration(now, moderator.getId(), note, null))
				.inc("rev", 1).set("updatedAt", now);
		ModerationCase held = mongo.findAndModify(query, claim, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (held == null) {
			throw CaseSteps.stale(mongo, id);
		}
		RestoreOutcome outcome;
		try {
			outcome = followUps.restoreFor(held);
		}
		catch (RuntimeException failed) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("restoration"),
					ModerationCase.class);
			throw failed;
		}
		Update done = after.apply(outcome)
				.set("restoration.outcome", outcome)
				.set("noticeChangedAt", now)
				.set("followUpDue", now)
				.set("retainUntil", Retention.afterAppeal(held.getRetainUntil(), now, retentionMonths()));
		ModerationCase undone = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)), done,
				FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
		// The comment is back: what its reporters fold away is theirs to see again (HIN-173).
		mongo.updateMulti(Query.query(Criteria.where("caseId").is(id)), new Update().set("removed", false),
				ContentReport.class);
		return new Undone(undone, outcome);
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

	int retentionMonths() {
		ServerSettings.Moderation moderation = settings.get().getModeration();
		return moderation == null ? 6 : moderation.retentionMonthsOrDefault();
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
