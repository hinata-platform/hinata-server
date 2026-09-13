package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The person's route to a correction they cannot make themselves (Art. 16 DSGVO),
 * the answer that comes back, and the days an administrator opens for them.
 *
 * <p>Two kinds of ask. An entry that exists but is frozen — by the lock date or a
 * submitted period — is asked about by its owner and reaches whoever can lift the
 * freeze. Days that cannot be recorded at all, because they lie beyond
 * {@code maxDaysBack} or before the lock date, have no entry to ask about, so the ask
 * names the span and goes to the administrators (R9).
 *
 * <p>Asking changes nothing, and neither does answering. What does is a separate,
 * audited act: a reopen for a submitted period, or a {@link TimeBackfillGrant} — days
 * opened for this one person, with a reason, for a limited time. The grant replaces
 * the lock exception as the answer to a personal request, because an exception opens
 * the days for everyone and publishes its reason, which here would be a sentence
 * about a person.
 *
 * <p>Independent of whether approvals are switched on, and of whether the audit log
 * records these events: the requests live in {@link TimeCorrectionRequest}, the audit
 * log only repeats them.
 */
@Service
@RequiredArgsConstructor
public class TimeCorrectionService {

	/** Most requests one inbox page carries. */
	static final int PAGE_MAX = 100;

	/** How long days opened for a person stay open. Long enough to catch up, short enough to end. */
	static final Duration GRANT_LIFETIME = Duration.ofDays(14);

	/** Most requests the entry sheet lists for one entry. */
	static final int ENTRY_REQUESTS_MAX = 20;

	private final TimeTrackingService entries;
	private final TimeLocks locks;
	private final TimeApprovers approvers;
	private final TimeTrackingSettings policy;
	private final TimeCorrectionRequestRepository requests;
	private final TimeBackfillGrantRepository grants;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/**
	 * One request as a reader sees it. Names are resolved when read and never stored,
	 * so an account deleted since shows as nobody rather than as the name it had.
	 * {@code grantable} says whether this reader can answer by opening the days.
	 */
	public record CorrectionRequest(String id, TimeCorrectionRequest.Kind kind, String entryId,
			LocalDate date, LocalDate from, LocalDate to, String projectId, String reason, String note,
			String requesterId, String requesterLabel, Instant at, Answer answer, boolean grantable) {
	}

	public record Answer(String note, String byId, String byLabel, Instant at, boolean granted) {
	}

	/** Days opened for a person, as the administrators list them. */
	public record Grant(String id, String userId, String userLabel, LocalDate from, LocalDate to,
			String note, String grantedById, String grantedByLabel, Instant grantedAt,
			Instant expiresAt) {
	}

	// --- asking --------------------------------------------------------------------

	/**
	 * Asks for a frozen entry of one's own to be opened.
	 *
	 * <p>An entry that is <em>not</em> frozen is refused. Without that the route would
	 * be a way to notify a project's leads about any entry at all.
	 */
	public void request(String workItemId, String note, User user) {
		WorkItem item = entries.requireOwn(workItemId, user);
		String reason = requiredNote(note);
		TimeLocks.LockState state =
				locks.lockStateFor(item.getUserId(), item.getProjectId(), item.getDate());
		if (state == null) {
			throw ApiException.badRequest("error.time.entryNotLocked");
		}
		TimeCorrectionRequest saved = insertOnce(TimeCorrectionRequest.builder()
				.kind(TimeCorrectionRequest.Kind.ENTRY)
				.userId(user.getId())
				.workItemId(item.getId())
				.date(item.getDate())
				.projectId(item.getProjectId())
				.reason(state.reason())
				.note(reason)
				.day(today())
				.createdAt(clock.instant())
				.build(), "error.time.correctionAlreadyRequested");
		boolean approval = state.reason() == TimePolicy.LockReason.APPROVAL;
		Set<String> recipients = approval
				? approvers.approverIds(item.getProjectId())
				: approvers.adminIds();
		recipients.remove(user.getId());
		if (!recipients.isEmpty()) {
			notifications.notifyTimeCorrectionRequested(recipients, user.getDisplayName(),
					approval ? "/time/approvals" : "/admin?section=timeTracking");
		}
		audit.event(AuditAction.TIME_CORRECTION_REQUESTED).actor(user)
				.target(item.getId(), String.valueOf(item.getDate()))
				.meta("workItem", item.getId())
				.meta("request", saved.getId())
				.meta("date", String.valueOf(item.getDate()))
				.meta("project", item.getProjectId())
				.meta("reason", state.reason().name())
				.meta("note", reason)
				.log();
	}

	/**
	 * Asks the administrators to open days that cannot be recorded yet.
	 *
	 * <p>Only when a day actually needs it: the first day of the span lies beyond the
	 * recording limit or before the lock date. The rest of the span may already be open —
	 * somebody catching up on three weeks names the three weeks, not the part of them the
	 * limit happens to cut off. Bounded like a lock exception, at most
	 * {@link TimePolicy#PERIOD_MAX_DAYS} days and not in the future, so what arrives is
	 * something an administrator can grant as asked. One per person per day.
	 */
	public void requestBackfill(LocalDate from, LocalDate to, String note, User user) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.time.lockExceptionInvalid");
		}
		TimeTrackingService.assertStorable(from, to, "error.time.rangeOutOfBounds");
		// Counted, never offset: plusDays on a date near the end of the calendar throws.
		if (ChronoUnit.DAYS.between(from, to) >= TimePolicy.PERIOD_MAX_DAYS) {
			throw ApiException.badRequest("error.time.periodTooLong");
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), entries.zoneOf(user));
		if (to.isAfter(today)) {
			throw ApiException.badRequest("error.time.dateInFuture");
		}
		LocalDate oldest = today.minusDays(policy.maxDaysBack());
		LocalDate lock = locks.lockBefore(null);
		TimePolicy.LockReason reason;
		if (from.isBefore(oldest)) {
			reason = TimePolicy.LockReason.MAX_DAYS_BACK;
		}
		else if (lock != null && from.isBefore(lock)) {
			reason = TimePolicy.LockReason.LOCK_DATE;
		}
		else {
			throw ApiException.badRequest("error.time.backfillNotNeeded");
		}
		String text = requiredNote(note);
		TimeCorrectionRequest saved = insertOnce(TimeCorrectionRequest.builder()
				.kind(TimeCorrectionRequest.Kind.SPAN)
				.userId(user.getId())
				.from(from)
				.to(to)
				.reason(reason)
				.note(text)
				.day(today())
				.createdAt(clock.instant())
				.build(), "error.time.backfillAlreadyRequested");
		Set<String> recipients = approvers.adminIds();
		recipients.remove(user.getId());
		if (!recipients.isEmpty()) {
			notifications.notifyTimeBackfillRequested(recipients, user.getDisplayName(),
					"/admin?section=timeTracking");
		}
		audit.event(AuditAction.TIME_BACKFILL_REQUESTED).actor(user)
				.target(user.getId(), from + " – " + to)
				.meta("request", saved.getId())
				.meta("from", String.valueOf(from))
				.meta("to", String.valueOf(to))
				.meta("reason", reason.name())
				.meta("note", text)
				.log();
	}

	// --- reading -------------------------------------------------------------------

	/**
	 * The requests this person can answer, newest first.
	 *
	 * <p>An administrator answers every request, because every freeze can be lifted by
	 * one. A lead answers the requests about submitted periods of the projects they
	 * lead — the lock date is not theirs to open, and neither are days beyond the
	 * limit. Nobody sees their own requests here: they are the one asking.
	 */
	public Page<CorrectionRequest> inbox(int page, int size, User user) {
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX),
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
		Criteria criteria = Criteria.where("userId").ne(user.getId());
		if (!user.isAdmin()) {
			Collection<String> led = approvers.ledProjectIds(user);
			if (led.isEmpty()) {
				return Page.empty(pageable);
			}
			criteria = criteria.and("reason").is(TimePolicy.LockReason.APPROVAL)
					.and("projectId").in(led);
		}
		Query query = Query.query(criteria);
		List<TimeCorrectionRequest> rows =
				mongo.find(Query.of(query).with(pageable), TimeCorrectionRequest.class);
		Map<String, String> labels = labelsOf(rows);
		List<CorrectionRequest> mapped = rows.stream().map(row -> view(row, labels, user)).toList();
		return PageableExecutionUtils.getPage(mapped, pageable,
				() -> mongo.count(Query.of(query).limit(-1).skip(-1), TimeCorrectionRequest.class));
	}

	/**
	 * The requests about one entry, for the person whose entry it is — so they can
	 * read the answer where they look for it, whatever the audit log records.
	 */
	public List<CorrectionRequest> forEntry(String workItemId, User user) {
		WorkItem item = entries.requireOwn(workItemId, user);
		List<TimeCorrectionRequest> rows = mongo.find(Query.query(Criteria.where("workItemId")
						.is(item.getId()))
				.with(Sort.by(Sort.Order.desc("createdAt"))).limit(ENTRY_REQUESTS_MAX),
				TimeCorrectionRequest.class);
		Map<String, String> labels = labelsOf(rows);
		return rows.stream().map(row -> view(row, labels, user)).toList();
	}

	// --- answering -----------------------------------------------------------------

	/**
	 * Answers one request with a sentence the person who asked will read.
	 *
	 * <p>Once. The answer is written with a condition on there being none, so two
	 * people answering at the same moment cannot both succeed. Somebody who may not
	 * answer is told the request does not exist — the id of a colleague's correction
	 * is not something to confirm.
	 */
	public CorrectionRequest answer(String requestId, String note, User user) {
		TimeCorrectionRequest request = answerable(requestId, user);
		String text = requiredNote(note);
		TimeCorrectionRequest.Answer answer =
				new TimeCorrectionRequest.Answer(text, user.getId(), clock.instant(), false);
		claimAnswer(request, answer);
		audit.event(AuditAction.TIME_CORRECTION_ANSWERED).actor(user)
				.target(request.getUserId(), null)
				.meta("workItem", request.getWorkItemId())
				.meta("request", request.getId())
				.meta("date", request.getDate() == null ? null : request.getDate().toString())
				.meta("project", request.getProjectId())
				.meta("reason", String.valueOf(request.getReason()))
				.meta("note", text)
				.log();
		notifyAnswered(request, user);
		return view(request, labelsOf(List.of(request)), user);
	}

	/**
	 * Answers a request by opening the days for the person who asked.
	 *
	 * <p>Administrators only, and only where a grant is the way out: days that cannot
	 * be recorded yet, or an entry frozen by the lock date. A submitted period is
	 * never opened this way — hours somebody signed off are reopened by the approver.
	 *
	 * <p>The note is optional here, unlike on every other answer. The request already
	 * carries the person's reason, and the audit keeps who opened which days and when;
	 * a sentence asked for on top of that only held the administrator up.
	 */
	public CorrectionRequest grant(String requestId, String note, User user) {
		if (!user.isAdmin()) {
			throw ApiException.notFound("timeCorrectionRequest");
		}
		TimeCorrectionRequest request = answerable(requestId, user);
		if (!grantable(request)) {
			throw ApiException.badRequest("error.time.grantNotApplicable");
		}
		String text = TimeNotes.trimmed(note);
		Instant now = clock.instant();
		claimAnswer(request, new TimeCorrectionRequest.Answer(text, user.getId(), now, true));
		boolean span = request.getKind() == TimeCorrectionRequest.Kind.SPAN;
		TimeBackfillGrant grant = grants.save(TimeBackfillGrant.builder()
				.userId(request.getUserId())
				.from(span ? request.getFrom() : request.getDate())
				.to(span ? request.getTo() : request.getDate())
				.note(text)
				.grantedBy(user.getId())
				.grantedAt(now)
				.expiresAt(now.plus(GRANT_LIFETIME))
				.requestId(request.getId())
				.build());
		audit.event(AuditAction.TIME_BACKFILL_GRANTED).actor(user)
				.target(request.getUserId(), grant.getFrom() + " – " + grant.getTo())
				.meta("workItem", request.getWorkItemId())
				.meta("request", request.getId())
				.meta("grant", grant.getId())
				.meta("from", String.valueOf(grant.getFrom()))
				.meta("to", String.valueOf(grant.getTo()))
				.meta("expiresAt", String.valueOf(grant.getExpiresAt()))
				.meta("reason", String.valueOf(request.getReason()))
				.meta("note", text)
				.log();
		notifyAnswered(request, user);
		return view(request, labelsOf(List.of(request)), user);
	}

	// --- grants --------------------------------------------------------------------

	/** Every grant still running, newest first. Administrators only. */
	public Page<Grant> activeGrants(int page, int size, User user) {
		requireAdmin(user);
		Pageable pageable = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), Sort.by(Sort.Order.desc("grantedAt")));
		Page<TimeBackfillGrant> found = grants.findByExpiresAtAfter(clock.instant(), pageable);
		Set<String> ids = new LinkedHashSet<>();
		found.forEach(grant -> {
			ids.add(grant.getUserId());
			ids.add(grant.getGrantedBy());
		});
		Map<String, String> labels = labelsOf(ids);
		return found.map(grant -> new Grant(grant.getId(), grant.getUserId(),
				labels.get(grant.getUserId()), grant.getFrom(), grant.getTo(), grant.getNote(),
				grant.getGrantedBy(), labels.get(grant.getGrantedBy()), grant.getGrantedAt(),
				grant.getExpiresAt()));
	}

	/** Takes a grant back before it ends. Administrators only; audited. */
	public void revokeGrant(String grantId, User user) {
		requireAdmin(user);
		TimeBackfillGrant grant = grants.findById(grantId)
				.orElseThrow(() -> ApiException.notFound("timeBackfillGrant"));
		grants.delete(grant);
		audit.event(AuditAction.TIME_BACKFILL_REVOKED).actor(user)
				.target(grant.getUserId(), grant.getFrom() + " – " + grant.getTo())
				.meta("grant", grant.getId())
				.meta("from", String.valueOf(grant.getFrom()))
				.meta("to", String.valueOf(grant.getTo()))
				.log();
	}

	// --- helpers -------------------------------------------------------------------

	private TimeCorrectionRequest answerable(String requestId, User user) {
		TimeCorrectionRequest request = requests.findById(requestId)
				.filter(found -> mayAnswer(found, user))
				.orElseThrow(() -> ApiException.notFound("timeCorrectionRequest"));
		if (user.getId().equals(request.getUserId())) {
			throw ApiException.forbidden("error.time.correctionOwnRequest");
		}
		if (request.getAnswer() != null) {
			throw ApiException.conflict("error.time.correctionAlreadyAnswered");
		}
		return request;
	}

	/** Writes the answer only while there is none; the loser of a race is told so. */
	private void claimAnswer(TimeCorrectionRequest request, TimeCorrectionRequest.Answer answer) {
		long written = mongo.updateFirst(Query.query(Criteria.where("_id").is(request.getId())
						.and("answer").is(null)),
				new Update().set("answer", answer), TimeCorrectionRequest.class).getModifiedCount();
		if (written == 0) {
			throw ApiException.conflict("error.time.correctionAlreadyAnswered");
		}
		request.setAnswer(answer);
	}

	private boolean mayAnswer(TimeCorrectionRequest request, User user) {
		if (user.isAdmin()) {
			return true;
		}
		return request.getKind() == TimeCorrectionRequest.Kind.ENTRY
				&& request.getReason() == TimePolicy.LockReason.APPROVAL
				&& approvers.leads(request.getProjectId(), user);
	}

	private static boolean grantable(TimeCorrectionRequest request) {
		return request.getKind() == TimeCorrectionRequest.Kind.SPAN
				|| request.getReason() == TimePolicy.LockReason.LOCK_DATE;
	}

	private void notifyAnswered(TimeCorrectionRequest request, User answeredBy) {
		if (request.getUserId() != null) {
			users.findById(request.getUserId()).ifPresent(owner ->
					notifications.notifyTimeCorrectionAnswered(owner, answeredBy.getDisplayName(), "/time"));
		}
	}

	/** Inserts once; the unique index turns a second request of the day into a 409. */
	private TimeCorrectionRequest insertOnce(TimeCorrectionRequest request, String conflictKey) {
		try {
			return mongo.insert(request);
		}
		catch (DuplicateKeyException alreadyAsked) {
			throw ApiException.conflict(conflictKey);
		}
	}

	private CorrectionRequest view(TimeCorrectionRequest row, Map<String, String> labels, User reader) {
		TimeCorrectionRequest.Answer answer = row.getAnswer();
		return new CorrectionRequest(row.getId(), row.getKind(), row.getWorkItemId(), row.getDate(),
				row.getFrom(), row.getTo(), row.getProjectId(),
				row.getReason() == null ? null : row.getReason().name(), row.getNote(),
				row.getUserId(), labels.get(row.getUserId()), row.getCreatedAt(),
				answer == null ? null : new Answer(answer.getNote(), answer.getById(),
						labels.get(answer.getById()), answer.getAt(), answer.isGranted()),
				answer == null && reader.isAdmin() && grantable(row)
						&& !reader.getId().equals(row.getUserId()));
	}

	private Map<String, String> labelsOf(List<TimeCorrectionRequest> rows) {
		Set<String> ids = new LinkedHashSet<>();
		for (TimeCorrectionRequest row : rows) {
			ids.add(row.getUserId());
			if (row.getAnswer() != null) {
				ids.add(row.getAnswer().getById());
			}
		}
		return labelsOf(ids);
	}

	/** Display names of the accounts that still exist; a deleted one stays nameless. */
	private Map<String, String> labelsOf(Collection<String> ids) {
		List<String> wanted = ids.stream().filter(Objects::nonNull).toList();
		Map<String, String> labels = new HashMap<>();
		if (wanted.isEmpty()) {
			return labels;
		}
		users.findAllById(wanted).forEach(user -> labels.put(user.getId(),
				user.getDisplayName() != null ? user.getDisplayName() : user.getUsername()));
		return labels;
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	private static void requireAdmin(User user) {
		if (!user.isAdmin()) {
			throw ApiException.forbidden("error.time.lockExceptionsAdminOnly");
		}
	}

	private static String requiredNote(String note) {
		String reason = TimeNotes.trimmed(note);
		if (reason == null) {
			throw ApiException.badRequest("error.time.approvalNoteRequired");
		}
		return reason;
	}
}
