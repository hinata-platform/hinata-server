package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.moderation.CaseAccess.Guarded;
import com.ahmadre.hinata.moderation.CaseAccess.Purpose;
import com.ahmadre.hinata.moderation.CaseViews.CaseDetail;
import com.ahmadre.hinata.moderation.CaseViews.CaseState;
import com.ahmadre.hinata.moderation.CaseViews.CaseSummary;
import com.ahmadre.hinata.moderation.CaseViews.PersonRef;
import com.ahmadre.hinata.moderation.CaseViews.ReportView;
import com.ahmadre.hinata.moderation.CaseViews.Summary;
import com.ahmadre.hinata.moderation.CaseViews.View;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cases as moderators work them (HIN-170): the lists and their numbers, one case opened, taking it,
 * uncovering a reporter, opening withheld content, looking at a file, and a case of one's own.
 * Deciding comes in HIN-171.
 */
@Service
@RequiredArgsConstructor
public class CaseService {

	static final int MAX_PAGE = 100;

	/** Reports shown with an opened case; the rest come a page at a time. */
	static final int FIRST_REPORTS = 20;

	/** A case is overdue after a day, and somebody else's claim can be taken over after two hours. */
	static final Duration OVERDUE = Duration.ofHours(24);
	static final Duration CLAIM_STALE = Duration.ofHours(2);

	/** Times one person may open withheld content alone, with nobody else there, in a day. */
	static final int SOLO_REVEALS_PER_DAY = 5;

	private static final String HISTORY = "history";

	private final CaseAccess access;
	private final ModerationAccess moderators;
	private final MongoTemplate mongo;
	private final UserRepository users;
	private final List<ContentSurface> surfaces;
	private final StorageService storage;
	private final AuditService audit;
	private final ModerationNotices notices;
	private final ContentReportService reports;
	private final Clock clock;

	// --- lists and numbers ---------------------------------------------------------------------

	/** One page of [view] for the signed-in moderator, without the cases they wrote content for. */
	public Page<CaseSummary> list(View view, int page, int size) {
		User viewer = moderators.require();
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE));
		Criteria filter = viewFilter(view);
		Query query = Query.query(new Criteria().andOperator(filter,
						Criteria.where("contributorIds").ne(viewer.getId())))
				.with(sortOf(view)).with(request);
		query.fields().exclude(HISTORY, "evidence", "decision.facts", "decision.internalNote",
				"decision.messageToAuthor", "appeal.text");
		List<ModerationCase> cases = mongo.find(query, ModerationCase.class);
		long total = mongo.count(Query.query(filter), ModerationCase.class) - mine(viewer, filter);
		return new PageImpl<>(summaries(cases, viewer), request, Math.max(total, 0));
	}

	/** The pills: open, urgent, overdue and appealed cases, each without what the reader wrote. */
	public Summary summary() {
		User viewer = moderators.require();
		Instant overdueSince = clock.instant().minus(OVERDUE);
		Criteria open = viewFilter(View.OPEN);
		return new Summary(
				countWithout(viewer, open),
				countWithout(viewer, new Criteria().andOperator(open, Criteria.where("urgent").is(true))),
				countWithout(viewer, new Criteria().andOperator(open, Criteria.where("firstReportedAt").lt(overdueSince))),
				countWithout(viewer, viewFilter(View.APPEAL)));
	}

	private long countWithout(User viewer, Criteria filter) {
		return Math.max(mongo.count(Query.query(filter), ModerationCase.class) - mine(viewer, filter), 0);
	}

	/** How many cases of [filter] the reader wrote content for: usually none, off {@code contributor_status}. */
	private long mine(User viewer, Criteria filter) {
		return mongo.count(Query.query(new Criteria().andOperator(Criteria.where("contributorIds").is(viewer.getId()),
				filter)), ModerationCase.class);
	}

	private static Criteria viewFilter(View view) {
		return switch (view) {
			case OPEN -> Criteria.where("status").is(CaseStatus.OPEN);
			case DONE -> Criteria.where("status").is(CaseStatus.DECIDED);
			case APPEAL -> Criteria.where("appeal.state").is(Appeal.State.PENDING);
		};
	}

	private static Sort sortOf(View view) {
		return switch (view) {
			case OPEN -> Sort.by(Sort.Order.desc("urgent"), Sort.Order.asc("firstReportedAt"), Sort.Order.asc("_id"));
			case DONE -> Sort.by(Sort.Order.desc("decidedAt"), Sort.Order.desc("_id"));
			case APPEAL -> Sort.by(Sort.Order.asc("appeal.filedAt"), Sort.Order.asc("_id"));
		};
	}

	/** Rows for [cases]: one read for the people who took them, one for the reader's own reports. */
	private List<CaseSummary> summaries(List<ModerationCase> cases, User viewer) {
		if (cases.isEmpty()) {
			return List.of();
		}
		Set<String> assigneeIds = new HashSet<>();
		List<String> caseIds = new ArrayList<>();
		for (ModerationCase moderationCase : cases) {
			caseIds.add(moderationCase.getId());
			if (moderationCase.getAssigneeId() != null) {
				assigneeIds.add(moderationCase.getAssigneeId());
			}
		}
		Map<String, PersonRef> people = people(assigneeIds);
		Query own = Query.query(Criteria.where("caseId").in(caseIds).and("reporterKey").is(viewer.getId()));
		own.fields().include("caseId");
		Set<String> reportedByMe = new HashSet<>();
		mongo.find(own, ContentReport.class).forEach(report -> reportedByMe.add(report.getCaseId()));
		return cases.stream().map(moderationCase -> summaryOf(moderationCase,
				people.get(moderationCase.getAssigneeId()), reportedByMe.contains(moderationCase.getId()))).toList();
	}

	private Map<String, PersonRef> people(Set<String> ids) {
		Map<String, PersonRef> people = new HashMap<>();
		if (!ids.isEmpty()) {
			users.findAllById(ids).forEach(user -> people.put(user.getId(),
					new PersonRef(user.getId(), user.getDisplayName(), user.getAvatarUrl())));
		}
		return people;
	}

	static CaseSummary summaryOf(ModerationCase c, PersonRef assignee, boolean reportedByMe) {
		Decision decision = c.getDecision();
		return new CaseSummary(c.getId(), stateOf(c), c.isUrgent(), c.getTarget() == null ? null : c.getTarget().kind(),
				c.getCategories(), c.getSources(), c.getReportCount(), c.getFirstReportedAt(), c.getLocation(),
				c.getListExcerpt(), assignee, reportedByMe, decision == null ? null : decision.outcome(),
				decision != null && decision.outcome() == Decision.Outcome.ACTION && decision.removal() != null,
				false, c.getRev());
	}

	/** The one state of a case, as the list shows it. */
	static CaseState stateOf(ModerationCase c) {
		Appeal appeal = c.getAppeal();
		if (appeal != null && appeal.state() == Appeal.State.PENDING) {
			return CaseState.APPEAL;
		}
		if (c.getStatus() == CaseStatus.OPEN) {
			return c.getAssigneeId() != null ? CaseState.IN_REVIEW : c.isUrgent() ? CaseState.URGENT : CaseState.OPEN;
		}
		boolean reversed = appeal != null && appeal.state() == Appeal.State.REVERSED;
		return c.getDecision() != null && c.getDecision().acted() && !reversed ? CaseState.ACTED : CaseState.NO_ACTION;
	}

	// --- one case --------------------------------------------------------------------------------

	/** Case [id] opened: the reported state, the current one while open, the first reports, the history. */
	public CaseDetail detail(String id) {
		Guarded guarded = access.require(id, Purpose.SEE);
		return detailOf(guarded.moderationCase(), guarded.moderator());
	}

	private CaseDetail detailOf(ModerationCase c, User moderator) {
		boolean withheld = withheldFrom(c, moderator);
		ContentSnapshot current = null;
		if (c.getStatus() == CaseStatus.OPEN && !withheld && c.getTarget() != null
				&& c.getTarget().kind() != ContentKind.OTHER) {
			current = surface(c.getTarget().kind()).forModeration(targetOf(c.getTarget())).orElse(null);
		}
		Query first = Query.query(Criteria.where("caseId").is(c.getId()))
				.with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id"))).limit(FIRST_REPORTS);
		List<ContentReport> found = mongo.find(first, ContentReport.class);
		long total = mongo.count(Query.query(Criteria.where("caseId").is(c.getId())), ContentReport.class);
		PersonRef assignee = c.getAssigneeId() == null ? null : people(Set.of(c.getAssigneeId())).get(c.getAssigneeId());
		boolean reportedByMe = access.reported(c, moderator.getId());
		return new CaseDetail(summaryOf(c, assignee, reportedByMe), withheld ? null : c.getEvidence(), current,
				withheld, c.getEvidenceReveal(), views(c, found, 0), total, c.getHistory(), c.getClaimedAt(),
				c.getDecision(), c.getAppeal());
	}

	/**
	 * Whether the content of [c] stays closed to [moderator]: reported without an account on content
	 * that is internal, not opened yet, and not content the moderator may see anyway.
	 */
	private boolean withheldFrom(ModerationCase c, User moderator) {
		if (!c.isEvidenceWithheld() || c.getEvidenceReveal() != null && c.getEvidenceReveal().revealed()) {
			return false;
		}
		ContentRef target = c.getTarget();
		return target == null || target.kind() == ContentKind.OTHER
				|| surface(target.kind()).snapshot(targetOf(target), moderator).isEmpty();
	}

	/** Further reports of case [id], a page at a time, in the order they came. */
	public Page<ReportView> reports(String id, int page, int size) {
		ModerationCase c = access.require(id, Purpose.SEE).moderationCase();
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE));
		Query query = Query.query(Criteria.where("caseId").is(c.getId()))
				.with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id"))).with(request);
		List<ContentReport> found = mongo.find(query, ContentReport.class);
		long total = mongo.count(Query.query(Criteria.where("caseId").is(c.getId())), ContentReport.class);
		return new PageImpl<>(views(c, found, (int) request.getOffset()), request, total);
	}

	/**
	 * [found] as moderators see them, numbered from [offset]: the source covered unless uncovered,
	 * and how many child-safety reports of the same source were found manifestly unfounded before.
	 */
	private List<ReportView> views(ModerationCase c, List<ContentReport> found, int offset) {
		Map<String, Integer> unfounded = unfoundedChildSafety(found);
		Set<String> uncovered = c.getRevealedReportIds() == null ? Set.of() : c.getRevealedReportIds();
		Set<String> uncoveredIds = new HashSet<>();
		for (ContentReport report : found) {
			if (uncovered.contains(report.getId()) && report.getReporterId() != null) {
				uncoveredIds.add(report.getReporterId());
			}
		}
		Map<String, PersonRef> people = people(uncoveredIds);
		List<ReportView> views = new ArrayList<>();
		for (int i = 0; i < found.size(); i++) {
			ContentReport report = found.get(i);
			PersonRef reporter = uncovered.contains(report.getId()) && report.getReporterId() != null
					? people.get(report.getReporterId()) : null;
			views.add(new ReportView(report.getId(), offset + i + 1, report.getSource(), report.getCategory(),
					report.getExplanation(), report.getCreatedAt(), reporter,
					unfounded.getOrDefault(report.getReporterKey(), 0)));
		}
		return views;
	}

	/** Per source of [found], its child-safety reports on cases decided as manifestly unfounded: two reads. */
	private Map<String, Integer> unfoundedChildSafety(List<ContentReport> found) {
		Set<String> keys = new HashSet<>();
		found.forEach(report -> keys.add(report.getReporterKey()));
		if (keys.isEmpty()) {
			return Map.of();
		}
		Query earlier = Query.query(Criteria.where("reporterKey").in(keys).and("category").is(ReportCategory.CHILD_SAFETY));
		earlier.fields().include("reporterKey", "caseId");
		List<ContentReport> childSafety = mongo.find(earlier, ContentReport.class);
		if (childSafety.isEmpty()) {
			return Map.of();
		}
		Set<String> caseIds = new HashSet<>();
		childSafety.forEach(report -> caseIds.add(report.getCaseId()));
		Query unfounded = Query.query(Criteria.where("_id").in(caseIds)
				.and("decision.outcome").is(Decision.Outcome.UNFOUNDED));
		unfounded.fields().include("_id");
		Set<String> unfoundedCases = new HashSet<>();
		mongo.find(unfounded, ModerationCase.class).forEach(decided -> unfoundedCases.add(decided.getId()));
		Map<String, Integer> counts = new HashMap<>();
		for (ContentReport report : childSafety) {
			if (unfoundedCases.contains(report.getCaseId())) {
				counts.merge(report.getReporterKey(), 1, Integer::sum);
			}
		}
		return counts;
	}

	// --- taking a case -------------------------------------------------------------------------

	/**
	 * Takes case [id] for the signed-in moderator at [rev]. Somebody else's claim older than two
	 * hours goes with [takeOver]. A 409 tells in {@code details.code} whether the case was decided
	 * meanwhile or only changed.
	 */
	public CaseSummary claim(String id, long rev, boolean takeOver) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		Instant now = clock.instant();
		List<Criteria> free = new ArrayList<>(List.of(Criteria.where("assigneeId").is(null),
				Criteria.where("assigneeId").is(moderator.getId())));
		if (takeOver) {
			free.add(Criteria.where("claimedAt").lt(now.minus(CLAIM_STALE)));
		}
		Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(id).and("rev").is(rev)
				.and("status").is(CaseStatus.OPEN), new Criteria().orOperator(free)));
		Update update = step(new Update().set("assigneeId", moderator.getId()).set("claimedAt", now),
				new CaseEvent(now, CaseEvent.Kind.CLAIMED, moderator.getId(), null));
		ModerationCase claimed = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (claimed == null) {
			throw stale(id);
		}
		audit.event(AuditAction.MODERATION_CASE_CLAIMED).actor(moderator).meta("case", id).log();
		return summaryOf(claimed, new PersonRef(moderator.getId(), moderator.getDisplayName(), moderator.getAvatarUrl()),
				access.reported(claimed, moderator.getId()));
	}

	/** Gives case [id] back, when the signed-in moderator holds it at [rev]. */
	public CaseSummary release(String id, long rev) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		User moderator = guarded.moderator();
		Instant now = clock.instant();
		Query query = Query.query(Criteria.where("_id").is(id).and("rev").is(rev).and("status").is(CaseStatus.OPEN)
				.and("assigneeId").is(moderator.getId()));
		Update update = step(new Update().unset("assigneeId").unset("claimedAt"),
				new CaseEvent(now, CaseEvent.Kind.RELEASED, moderator.getId(), null));
		ModerationCase released = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
				ModerationCase.class);
		if (released == null) {
			throw stale(id);
		}
		return summaryOf(released, null, access.reported(released, moderator.getId()));
	}

	/** A moderator's step: one more in the history, and the revision every later step must match. */
	private static Update step(Update update, CaseEvent event) {
		update.inc("rev", 1).set("updatedAt", event.at());
		update.push(HISTORY).slice(-ModerationCase.HISTORY_MAX).each(event);
		return update;
	}

	/** 409 for a step on a case that moved: decided meanwhile, or changed by somebody else. */
	private ApiException stale(String id) {
		ModerationCase now = mongo.findById(id, ModerationCase.class);
		String code = now != null && now.getStatus() == CaseStatus.DECIDED ? "decided" : "changed";
		return ApiException.conflict("error.moderation.caseChanged", Map.of("code", code));
	}

	// --- uncovering ------------------------------------------------------------------------------

	/** Why a reporter or withheld content is uncovered; the reason goes into the case's history. */
	public record RevealRequest(@NotBlank @Size(max = 500) String reason) {
	}

	/**
	 * Uncovers who filed report [reportId] of case [id], with a reason. Never for a case about the
	 * safety of children: a false accusation there must not be able to reach a name.
	 */
	public ReportView revealReporter(String id, String reportId, String reason) {
		Guarded guarded = access.require(id, Purpose.DECIDE);
		ModerationCase c = guarded.moderationCase();
		if (c.getCategories() != null && c.getCategories().contains(ReportCategory.CHILD_SAFETY)) {
			throw ApiException.forbidden("error.moderation.childSafetyCovered");
		}
		ContentReport report = Optional.ofNullable(mongo.findById(reportId, ContentReport.class))
				.filter(found -> c.getId().equals(found.getCaseId()))
				.orElseThrow(() -> ApiException.notFound("moderationReport"));
		Instant now = clock.instant();
		mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update()
				.addToSet("revealedReportIds", reportId)
				.set("updatedAt", now)
				.push(HISTORY).slice(-ModerationCase.HISTORY_MAX)
				.each(new CaseEvent(now, CaseEvent.Kind.REPORTER_REVEALED, guarded.moderator().getId(), reason)),
				ModerationCase.class);
		audit.event(AuditAction.MODERATION_REPORTER_REVEALED).actor(guarded.moderator()).meta("case", id).log();
		ModerationCase after = mongo.findById(id, ModerationCase.class);
		List<ReportView> views = views(after, List.of(report), 0);
		return views.getFirst();
	}

	/**
	 * Opens content somebody without an account reported on internal content (HIN-175). The first
	 * call asks, with a reason; a second person opens it. With nobody else to ask, the one asking
	 * opens it alone, at most {@value #SOLO_REVEALS_PER_DAY} times a day, and the admins hear of it.
	 */
	public CaseDetail revealEvidence(String id, String reason) {
		Guarded guarded = access.require(id, Purpose.SEE);
		ModerationCase c = guarded.moderationCase();
		User moderator = guarded.moderator();
		if (!withheldFrom(c, moderator)) {
			return detailOf(c, moderator);
		}
		Instant now = clock.instant();
		EvidenceReveal asked = c.getEvidenceReveal();
		if (asked == null || asked.requestedBy() == null) {
			c.setEvidenceReveal(new EvidenceReveal(moderator.getId(), now, reason, null, null));
			mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update()
					.set("evidenceReveal", c.getEvidenceReveal()).set("updatedAt", now)
					.push(HISTORY).slice(-ModerationCase.HISTORY_MAX)
					.each(new CaseEvent(now, CaseEvent.Kind.EVIDENCE_REQUESTED, moderator.getId(), reason)),
					ModerationCase.class);
			if (!access.eligibleModerators(c, Purpose.SECOND_PERSON).isEmpty()) {
				notices.confirmRequested(c);
				return detailOf(mongo.findById(id, ModerationCase.class), moderator);
			}
			return revealAlone(c, moderator, reason, now);
		}
		if (asked.requestedBy().equals(moderator.getId())) {
			if (!access.eligibleModerators(c, Purpose.SECOND_PERSON).isEmpty()) {
				throw ApiException.conflict("error.moderation.secondPersonNeeded");
			}
			return revealAlone(c, moderator, reason, now);
		}
		if (!access.eligible(moderator, c, Purpose.SECOND_PERSON)) {
			throw ApiException.conflict("error.moderation.conflictOfInterest");
		}
		return reveal(c, moderator, asked, reason, now);
	}

	private CaseDetail revealAlone(ModerationCase c, User moderator, String reason, Instant now) {
		long alone = mongo.count(Query.query(Criteria.where("evidenceReveal.requestedBy").is(moderator.getId())
				.and("evidenceReveal.revealedBy").is(moderator.getId())
				.and("evidenceReveal.revealedAt").gte(now.minus(Duration.ofDays(1)))), ModerationCase.class);
		if (alone >= SOLO_REVEALS_PER_DAY) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.moderation.tooManyReveals",
					Map.of("code", "moderation.tooManyReveals"));
		}
		CaseDetail opened = reveal(c, moderator, c.getEvidenceReveal(), reason, now);
		notices.adminAlert(c, "moderation.alert.alone");
		return opened;
	}

	private CaseDetail reveal(ModerationCase c, User moderator, EvidenceReveal asked, String reason, Instant now) {
		EvidenceReveal revealed = new EvidenceReveal(asked.requestedBy(), asked.requestedAt(), asked.reason(),
				moderator.getId(), now);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update()
				.set("evidenceReveal", revealed).set("evidenceWithheld", false).set("updatedAt", now)
				.push(HISTORY).slice(-ModerationCase.HISTORY_MAX)
				.each(new CaseEvent(now, CaseEvent.Kind.EVIDENCE_REVEALED, moderator.getId(), reason)),
				ModerationCase.class);
		audit.event(AuditAction.MODERATION_EVIDENCE_REVEALED).actor(moderator).meta("case", c.getId()).log();
		notices.teamNote(c, "moderation.notice.revealed");
		return detailOf(mongo.findById(c.getId(), ModerationCase.class), moderator);
	}

	// --- files ---------------------------------------------------------------------------------------

	/** A file of a case's evidence, streamed by the server, with what it is. */
	public record EvidenceFile(byte[] data, String contentType, String fileName) {
	}

	/**
	 * File [index] of case [id]'s evidence, through the server and never by a link that could be
	 * passed on. Never for a case about the safety of children: decided without looking (HIN-178).
	 * Every look is in the audit log.
	 */
	public EvidenceFile evidenceFile(String id, int index) {
		Guarded guarded = access.require(id, Purpose.SEE);
		ModerationCase c = guarded.moderationCase();
		if (c.getCategories() != null && c.getCategories().contains(ReportCategory.CHILD_SAFETY)) {
			throw ApiException.forbidden("error.moderation.childSafetyFilesClosed");
		}
		if (withheldFrom(c, guarded.moderator()) || c.getEvidence() == null || c.getEvidence().media() == null
				|| index < 0 || index >= c.getEvidence().media().size()) {
			throw ApiException.notFound("moderationCase");
		}
		MediaRef media = c.getEvidence().media().get(index);
		// After a removal the original is gone and the quarantine holds the copy.
		StorageService.StoredObject object = storage.getObject(media.objectKey())
				.or(() -> storage.getObject(Quarantine.keyFor(c.getId(), media.objectKey())))
				.orElseThrow(() -> ApiException.notFound("moderationCase"));
		audit.event(AuditAction.MODERATION_EVIDENCE_VIEWED).actor(guarded.moderator()).meta("case", id).log();
		String type = media.contentType() != null ? media.contentType() : object.contentType();
		return new EvidenceFile(object.data(), type, media.fileName());
	}

	// --- a case of one's own ---------------------------------------------------------------------

	/** A case moderation opens itself: on content the moderator sees, or on a person (HIN-172). */
	public record OwnCaseRequest(@NotNull @Valid TargetRef target, @NotNull ReportCategory category,
			@NotBlank @Size(max = 2000) String facts) {
	}

	/** Opens case for [request] as the signed-in moderator; answers its id. */
	public String openOwn(OwnCaseRequest request) {
		User moderator = moderators.require();
		if (request.target().kind() == ContentKind.OTHER) {
			throw ApiException.badRequest("error.moderation.scopeNotAllowed");
		}
		ContentSnapshot snapshot = surface(request.target().kind()).snapshot(request.target(), moderator)
				.orElseThrow(() -> ApiException.notFound("moderationCase"));
		if (snapshot.removed()) {
			throw ApiException.gone("error.moderation.removed");
		}
		if (snapshot.contributorIds().contains(moderator.getId())
				|| request.target().kind() == ContentKind.PERSON && moderator.getId().equals(request.target().id())) {
			throw ApiException.badRequest("error.moderation.ownContent");
		}
		ContentReportService.Target target = new ContentReportService.Target(snapshot.ref(), snapshot,
				ContentReportService.locationOf(snapshot));
		ContentReportService.Filed filed = reports.file(target, request.category(), request.facts(),
				new ContentReportService.Source(ReportSource.OWN_INITIATIVE, "own:" + moderator.getId(), null, null));
		audit.event(AuditAction.MODERATION_CASE_CREATED).actor(moderator).meta("case", filed.moderationCase().getId())
				.log();
		return filed.moderationCase().getId();
	}

	// --- helpers ---------------------------------------------------------------------------------

	private ContentSurface surface(ContentKind kind) {
		return surfaces.stream().filter(surface -> surface.kind() == kind).findFirst()
				.orElseThrow(() -> ApiException.badRequest("error.moderation.scopeNotAllowed"));
	}

	static TargetRef targetOf(ContentRef ref) {
		return new TargetRef(ref.kind(), ref.id(), ref.parentId());
	}
}
