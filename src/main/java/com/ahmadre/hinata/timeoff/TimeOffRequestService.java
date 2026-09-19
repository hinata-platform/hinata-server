package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asking for time off, deciding it, and taking it back.
 *
 * <p>The flow is the one HIN-88 settled for timesheets, because a second flow would be a second
 * set of rules about who may decide what, and they would drift: a request is submitted, somebody
 * other than the person decides it, every step is recorded, and a decision that was made twice at
 * once loses rather than books twice.
 *
 * <p><b>What the approval writes.</b> Exactly one absence and exactly one booking, and the request
 * keeps both ids. Cancelling reverses those two and nothing else. Nothing here recomputes a
 * balance: the ledger is append-only and every figure is a sum of it, so a cancellation is a
 * counter-booking rather than the removal of a row.
 *
 * <p><b>What it refuses, and what it merely says out loud.</b> Hard refusals are the rules the
 * type itself sets — more consecutive days than it allows, a balance it will not let go negative.
 * Everything else is a warning to whoever decides: short notice, a balance that will not cover it,
 * somebody else in the team away at the same time. A tool that refuses what the law leaves to
 * judgement takes the judgement away from the people the law gives it to (§ 7 Abs. 1 BUrlG).
 *
 * <p><b>Sickness never comes through here.</b> {@link #reportSick} is a different method with no
 * approver, no status and no path that can say no — § 5 EFZG knows a notification, not a
 * permission (R11). It is also where § 9 BUrlG lives: sickness that falls inside approved leave
 * gives the leave back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeOffRequestService {

	/** The largest page anybody may ask for, matching the rest of the module. */
	public static final int PAGE_MAX = 100;

	/** How far a clash query may look, so one read cannot sweep a year of everybody's absences. */
	public static final int CONFLICT_DAYS_MAX = 92;

	/** How many other people a clash line will name before it stops counting them out. */
	static final int CONFLICTS_MAX = 50;

	/** How many overlapping leaves one sick report will give back before it stops looking. */
	static final int OVERLAP_MAX = 20;

	private final TimeOffRequestRepository requests;
	private final TimeOffTypeService types;
	private final TimeOffWorkingDays workingDays;
	private final TimeOffAbsences absences;
	private final TimeOffApprovers approvers;
	private final TimeOffBalanceService balances;
	private final TimeOffAccess access;
	private final TimeOffRequestLimiter limiter;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final Clock clock;

	/** What somebody asks for. [first] and [last] are thousandths of a day for the edge days. */
	public record Draft(String typeId, LocalDate from, LocalDate to, Integer first, Integer last,
			String note, String substituteId) {
	}

	/**
	 * A request with the things a screen needs beside it, worked out once for a page rather than
	 * once per row.
	 *
	 * <p>[balanceShort] is a yes or no and never a figure: a lead deciding leave for their team
	 * learns whether the days are there, not how many the person has (R2, R10). [clashes] counts
	 * other people away across the same span, and names none of them — the line that names them is
	 * its own read, which a decider makes by opening the request.
	 */
	public record View(TimeOffRequest request, String personName, String typeKey, String typeSystemKey,
			boolean balanceShort, boolean shortNotice, int clashes) {
	}

	/** What a span would cost, for the form to show while somebody is still picking dates. */
	public record Preview(int milliDays, int workingDays, int holidays, int daysOff,
			boolean balanceShort, boolean shortNotice) {
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Somebody's own requests, newest first, optionally one status and one leave year.
	 *
	 * <p>The year narrows on [from], which is the field the list is ordered by and the third field
	 * of {@code user_status_from} — so it stays a range walk of the index rather than a filter
	 * after the fact.
	 */
	public Page<View> mine(User person, TimeOffRequest.Status status, Integer year, int page, int size) {
		Pageable request = pageOf(page, size);
		Page<TimeOffRequest> found;
		if (year == null) {
			found = status == null
					? requests.findByUserId(person.getId(), request)
					: requests.findByUserIdAndStatus(person.getId(), status, request);
		} else {
			LocalDate first = LocalDate.of(year, 1, 1);
			LocalDate last = LocalDate.of(year, 12, 31);
			found = status == null
					? requests.findByUserIdAndFromBetween(person.getId(), first, last, request)
					: requests.findByUserIdAndStatusAndFromBetween(person.getId(), status, first, last, request);
		}
		return found.map(viewsOf(found.getContent(), person, false)::get);
	}

	/**
	 * What [decider] has to decide.
	 *
	 * <p>Read off {@code approverIds}, which was frozen when the request was submitted — so a
	 * request stays in the inbox of the person who was asked, rather than moving because a team
	 * gained a lead halfway through.
	 */
	public Page<View> inbox(User decider, TimeOffRequest.Status status, int page, int size) {
		Pageable request = pageOf(page, size);
		Page<TimeOffRequest> found = status == null
				? requests.findByApproverIdsContains(decider.getId(), request)
				: requests.findByApproverIdsContainsAndStatus(decider.getId(), status, request);
		return found.map(viewsOf(found.getContent(), decider, true)::get);
	}

	/**
	 * One request, for anybody it concerns: the person, whoever may decide it, or a keeper.
	 *
	 * <p>Everybody else gets 404 rather than 403 — a stranger probing ids learns nothing from the
	 * difference between "not yours" and "not there".
	 */
	public TimeOffRequest get(String id, User viewer) {
		TimeOffRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("timeOffRequest"));
		if (!concerns(request, viewer)) {
			throw ApiException.notFound("timeOffRequest");
		}
		return request;
	}

	/**
	 * One request with everything a detail screen shows beside it.
	 *
	 * <p>The same shape a list row has, so a client has one model rather than two that drift. The
	 * clash count is in it here as well: on the screen where somebody decides, "two others are
	 * away then" is the fact § 7 Abs. 1 BUrlG makes the decision turn on.
	 */
	public View view(String id, User viewer) {
		return view(get(id, viewer), viewer);
	}

	/** As above for a request already in hand, so a write need not read it back. */
	public View view(TimeOffRequest request, User viewer) {
		return viewsOf(List.of(request), viewer, true).get(request);
	}

	/**
	 * What [draft] would cost [person], and what somebody should be told about it.
	 *
	 * <p>The same arithmetic the submission freezes, so the number in the form is the number on the
	 * request. Read-only, and it writes nothing.
	 */
	public Preview preview(User person, Draft draft) {
		TimeOffType type = types.require(person, draft.typeId());
		TimeOffSpan.Result span = span(person, type, draft);
		return new Preview(span.milliDays(), span.workingDays(), span.holidays(), span.daysOff(),
				balanceShort(person, type, draft.from(), span.milliDays()),
				shortNotice(type, draft.from()));
	}

	/**
	 * Who else is away between [from] and [to] — for a decider weighing one request against the
	 * team it leaves behind (§ 7 Abs. 1 BUrlG: somebody else's prior claim is a lawful reason).
	 *
	 * <p>Names and spans, never the type. Whether a colleague is on vacation or ill is not part of
	 * this decision, and a clash line that said so would be a way for anybody who ever decides a
	 * request to learn it (R10, R11).
	 */
	public List<Clash> conflicts(User decider, LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.timeOff.spanInvalid");
		}
		if (from.plusDays(CONFLICT_DAYS_MAX - 1L).isBefore(to)) {
			throw ApiException.badRequest("error.timeOff.spanTooLong", CONFLICT_DAYS_MAX);
		}
		List<TimeOffRequest.Status> live = List.of(TimeOffRequest.Status.SUBMITTED,
				TimeOffRequest.Status.APPROVED);
		Pageable window = PageRequest.of(0, CONFLICTS_MAX);
		// Asked of the database rather than filtered afterwards. A lead reading the first fifty
		// requests in the organisation and then keeping the ones that are theirs would be told
		// "nobody else is away" on exactly the busy weeks the line exists for.
		List<TimeOffRequest> found = access.isKeeper(decider)
				? requests.findByStatusInAndToGreaterThanEqualAndFromLessThanEqual(live, from, to, window)
				: requests.findByApproverIdsContainsAndStatusInAndToGreaterThanEqualAndFromLessThanEqual(
						decider.getId(), live, from, to, window);
		List<TimeOffRequest> theirs = found.stream()
				.filter(each -> !each.getUserId().equals(decider.getId()))
				.toList();
		List<String> ids = theirs.stream().map(TimeOffRequest::getUserId).distinct().toList();
		Map<String, String> names = new HashMap<>();
		for (User person : users.findAllById(ids)) {
			names.put(person.getId(), person.getDisplayName());
		}
		List<Clash> clashes = new ArrayList<>();
		for (TimeOffRequest each : theirs) {
			clashes.add(new Clash(each.getUserId(), names.get(each.getUserId()), each.getFrom(), each.getTo(),
					each.getStatus() == TimeOffRequest.Status.APPROVED));
		}
		return clashes;
	}

	/** Somebody else away at the same time: who, when, and whether it is already settled. */
	public record Clash(String userId, String name, LocalDate from, LocalDate to, boolean approved) {
	}

	/**
	 * Everything a page of rows needs beside the rows, in four reads however long the page is.
	 *
	 * <p>The names, the types, the balances and the clashes are each one query for the whole page.
	 * Asked per row they would be four per row, which is the shape A1 spent a review round taking
	 * out of the entitlement directory — and an inbox is the screen somebody opens every morning.
	 */
	private Map<TimeOffRequest, View> viewsOf(List<TimeOffRequest> rows, User viewer, boolean withClashes) {
		Map<TimeOffRequest, View> views = new IdentityHashMap<>();
		if (rows.isEmpty()) {
			return views;
		}
		List<String> userIds = rows.stream().map(TimeOffRequest::getUserId).distinct().toList();
		Map<String, String> names = new HashMap<>();
		for (User person : users.findAllById(userIds)) {
			names.put(person.getId(), person.getDisplayName());
		}
		Map<String, TimeOffType> catalogue = new HashMap<>();
		for (TimeOffType type : types.findAllById(
				rows.stream().map(TimeOffRequest::getTypeId).distinct().toList())) {
			catalogue.put(type.getId(), type);
		}
		Set<Integer> years = rows.stream().map(row -> row.getFrom().getYear()).collect(Collectors.toSet());
		Map<String, Map<String, Map<Integer, Integer>>> remaining =
				balances.remainingByPerson(userIds, years);
		Map<TimeOffRequest, Integer> clashes = withClashes ? clashesAcross(rows, viewer) : Map.of();
		for (TimeOffRequest row : rows) {
			TimeOffType type = catalogue.get(row.getTypeId());
			views.put(row, new View(row, names.get(row.getUserId()),
					type == null ? null : type.getKey(),
					type == null ? null : type.getSystemKey(),
					type != null && balanceFallsShort(type, remaining, row),
					type != null && shortNotice(type, row.getFrom()),
					clashes.getOrDefault(row, 0)));
		}
		return views;
	}

	/** Whether what is left will not cover [row]. A yes or a no; the figure never leaves here. */
	private static boolean balanceFallsShort(TimeOffType type,
			Map<String, Map<String, Map<Integer, Integer>>> remaining, TimeOffRequest row) {
		if (!type.countsAgainstBalance() || type.isUnlimited()) {
			return false;
		}
		int left = remaining.getOrDefault(row.getUserId(), Map.of())
				.getOrDefault(row.getTypeId(), Map.of())
				.getOrDefault(row.getFrom().getYear(), 0);
		return left - row.milliDays() < 0;
	}

	/**
	 * How many other people are away across each row's span, counted from one read.
	 *
	 * <p>One query for the widest span the page covers, then counted in memory. Per row it would
	 * be a query per row, and the answer is a single number on a card.
	 *
	 * <p>Only for an inbox. On somebody's own list the same read would return nothing for almost
	 * everybody — who else is away is a decider's question — and cost a query to say so.
	 */
	private Map<TimeOffRequest, Integer> clashesAcross(List<TimeOffRequest> rows, User viewer) {
		Map<TimeOffRequest, Integer> counts = new IdentityHashMap<>();
		LocalDate first = rows.stream().map(TimeOffRequest::getFrom).min(LocalDate::compareTo).orElse(null);
		LocalDate last = rows.stream().map(TimeOffRequest::getTo).max(LocalDate::compareTo).orElse(null);
		if (first == null || last == null || first.plusDays(CONFLICT_DAYS_MAX - 1L).isBefore(last)) {
			// A page reaching across more than a quarter would make this the very sweep the clash
			// query is bounded to avoid. The cards then simply carry no count.
			return counts;
		}
		List<Clash> around = conflicts(viewer, first, last);
		for (TimeOffRequest row : rows) {
			int count = 0;
			for (Clash clash : around) {
				if (!clash.userId().equals(row.getUserId())
						&& !clash.from().isAfter(row.getTo()) && !clash.to().isBefore(row.getFrom())) {
					count++;
				}
			}
			counts.put(row, count);
		}
		return counts;
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Files a request for [person] and routes it.
	 *
	 * <p>A type nobody has to approve is decided as it arrives, rather than filed into an inbox
	 * that would never look at it — that is what {@code AUTO} means, and a type whose approval was
	 * switched off behaves the same way for the same reason. The person is told, and the step is in
	 * the history, so an automatic yes is as visible as a considered one.
	 */
	public TimeOffRequest submit(User person, Draft draft) {
		limiter.require(person.getId());
		TimeOffType type = types.require(person, draft.typeId());
		if (type.getKind() == TimeOffType.Kind.SICK) {
			// Not a refusal of the absence — a refusal of the queue. § 5 EFZG knows a notification,
			// and the route that takes one is next door.
			throw ApiException.badRequest("error.timeOff.sickNotRequested");
		}
		TimeOffSpan.Result span = span(person, type, draft);
		assertWithinTypeRules(type, span, draft);

		boolean decidesItself = !type.requiresApproval() || type.approverRule() == TimeOffType.ApproverRule.AUTO;
		Set<String> audience = new LinkedHashSet<>(approvers.of(type, person));
		if (audience.isEmpty()) {
			// `of` answers nobody for AUTO on purpose — there is nothing to route. Stored, that
			// would be a request no inbox can ever find, and an automatic approval that fails for
			// any reason would leave exactly such a request behind. The administrators are the
			// floor here as everywhere else.
			audience = approvers.adminIds();
			audience.remove(person.getId());
		}

		TimeOffRequest request = TimeOffRequest.builder()
				.userId(person.getId())
				.typeId(type.getId())
				.from(draft.from())
				.to(draft.to())
				.firstDayMilliDays(portion(draft.first()))
				.lastDayMilliDays(portion(draft.last()))
				.milliDays(span.milliDays())
				.workingDays(span.workingDays())
				.holidays(span.holidays())
				.note(note(draft.note()))
				.substituteId(substitute(person, draft.substituteId()))
				.status(TimeOffRequest.Status.SUBMITTED)
				.approverIds(new ArrayList<>(audience))
				.build();
		request.record(TimeOffRequest.Event.builder()
				.at(clock.instant()).by(person.getId()).to(TimeOffRequest.Status.SUBMITTED)
				.note(request.getNote()).build());
		request.setUpdatedAt(clock.instant());
		TimeOffRequest saved = requests.save(request);
		audited(AuditAction.TIME_OFF_REQUEST_SUBMITTED, saved, person);

		if (decidesItself) {
			return decideInternally(saved, person, person, TimeOffRequest.Status.APPROVED, null, true, type);
		}
		notifications.notifyTimeOffRequested(audience, person.getDisplayName(), "/absences/inbox");
		notifySubstitute(saved, person);
		return saved;
	}

	/** Approves, with an optional word about why. */
	public TimeOffRequest approve(String id, String note, User decider) {
		TimeOffRequest request = decidable(id, decider);
		User person = personOf(request);
		return decideInternally(request, person, decider, TimeOffRequest.Status.APPROVED, note, false, null);
	}

	/**
	 * Rejects, and the reason is required.
	 *
	 * <p>§ 7 Abs. 1 BUrlG allows a refusal only for urgent operational reasons or somebody else's
	 * prior claim. A refusal that names neither is not one anybody can check, so there is no way to
	 * send one from here.
	 */
	public TimeOffRequest reject(String id, String note, User decider) {
		String reason = note == null ? "" : note.strip();
		if (reason.isEmpty()) {
			throw ApiException.badRequest("error.timeOff.decisionReasonRequired");
		}
		TimeOffRequest request = decidable(id, decider);
		User person = personOf(request);
		return decideInternally(request, person, decider, TimeOffRequest.Status.REJECTED, reason, false, null);
	}

	/** Takes a request back, before anybody has decided it. Only the person who made it. */
	public TimeOffRequest withdraw(String id, User person) {
		TimeOffRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("timeOffRequest"));
		if (!request.getUserId().equals(person.getId())) {
			throw ApiException.notFound("timeOffRequest");
		}
		if (!request.getStatus().open()) {
			throw ApiException.conflict("error.timeOff.requestDecided");
		}
		return transition(request, person, TimeOffRequest.Status.WITHDRAWN, null,
				AuditAction.TIME_OFF_REQUEST_WITHDRAWN);
	}

	/**
	 * Cancels leave that was already approved: the absence goes, and the days come back.
	 *
	 * <p>The person may cancel their own while all of it is still ahead of them — nothing has been
	 * taken yet, and making somebody ask permission to *not* be away would be a rule with no
	 * purpose. Whoever decided it is told, because the plan they agreed to has changed. Once any of
	 * it is in the past it takes a keeper, since by then it is a record of what happened rather
	 * than a plan.
	 */
	public TimeOffRequest cancel(String id, String note, User actor) {
		TimeOffRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("timeOffRequest"));
		if (!concerns(request, actor)) {
			throw ApiException.notFound("timeOffRequest");
		}
		if (request.getStatus() != TimeOffRequest.Status.APPROVED) {
			throw ApiException.conflict("error.timeOff.requestNotApproved");
		}
		boolean started = !request.getFrom().isAfter(LocalDate.now(clock));
		boolean keeper = access.isKeeper(actor);
		if (!keeper && (started || !request.getUserId().equals(actor.getId()))) {
			throw ApiException.forbidden("error.timeOff.cancelNotYours");
		}
		User person = personOf(request);
		// The claim first, for the same reason as a decision: two cancellations that both reversed
		// before either saved would credit the days back twice.
		String absenceId = request.getTimeOffId();
		request.setTimeOffId(null);
		TimeOffRequest saved = transition(request, actor, TimeOffRequest.Status.CANCELLED, note,
				AuditAction.TIME_OFF_REQUEST_CANCELLED);
		absences.remove(actor, absenceId);
		returnDays(saved, person, actor, saved.milliDays());
		requests.save(saved);
		notifications.notifyTimeOffCancelled(actor.getId().equals(person.getId()) ? null : person,
				othersOf(saved, actor), person.getDisplayName(), "/absences/requests");
		return saved;
	}

	/**
	 * Reports sickness. One step, effective at once, and there is no path through here that says
	 * no.
	 *
	 * <p>No approver, no reason, no certificate, and retroactive within the same bounds any
	 * absence has. § 5 EFZG obliges a person to say they are ill, not to ask whether they may be;
	 * since 2023 an employer retrieves the certificate from the health insurer under § 109 SGB IV,
	 * which is why there is nowhere here to upload one — that would be Art. 9 data in a project
	 * tool (R11).
	 *
	 * <p>§ 9 BUrlG lives here too: days of approved leave the sickness falls on are given back and
	 * the leave is shortened, because leave that somebody spent ill was not leave.
	 */
	public Sick reportSick(User person, LocalDate from, LocalDate to, boolean halfDay, String typeId) {
		// Metered so the count is honest, never refused: § 5 EFZG knows a notification and not a
		// permission, so there is no path through here that can say no (R11).
		limiter.allow(person.getId());
		TimeOffType type = sickType(person, typeId);
		LocalDate last = to == null ? from : to;
		TimeOffSpan.Result span = workingDays.of(person.getId(), from, last,
				halfDay ? TimeOffSpan.DAY / 2 : TimeOffSpan.DAY, TimeOffSpan.DAY);

		String absenceId = absences.enter(person, person, type.getId(), from, last, halfDay, null);
		String ledgerId = null;
		if (type.countsAgainstBalance() && span.milliDays() > 0) {
			ledgerId = book(person, person, type, from, -span.milliDays(), absenceId, null).getId();
		}
		int returned = returnOverlappedLeave(person, from, last);
		audit.event(AuditAction.TIME_OFF_SICK_REPORTED).actor(person).target(person)
				.meta("from", String.valueOf(from))
				.meta("to", String.valueOf(last))
				.log();
		return new Sick(absenceId, ledgerId, span.milliDays(), returned);
	}

	/** What reporting sickness produced, including any leave § 9 BUrlG gave back. */
	public record Sick(String absenceId, String ledgerId, int milliDays, int returnedMilliDays) {
	}

	// ------------------------------------------------------------------ the middle

	/**
	 * The one place a request changes hands.
	 *
	 * <p><b>The claim comes first.</b> The status moves under the version guard before anything is
	 * written because of it, so two people pressing approve in the same second produce one winner
	 * and one 409 — and the loser writes nothing. The other order reads better and is wrong: both
	 * would pass the status check, both would enter an absence and book a week off a balance, and
	 * only then would one of the saves fail. A balance that lost two weeks for one is a balance
	 * nobody can be asked to trust, and there is no transaction here to undo the second.
	 *
	 * <p>The price is the other end: a claim that succeeds and a write that then fails would leave
	 * a request marked approved with nothing behind it. That one is compensated — the claim is put
	 * back and the refusal travels on — which is possible precisely because only one thread ever
	 * gets there.
	 */
	private TimeOffRequest decideInternally(TimeOffRequest request, User person, User decider,
			TimeOffRequest.Status target, String note, boolean automatic, TimeOffType known) {
		TimeOffType type = null;
		if (target == TimeOffRequest.Status.APPROVED) {
			type = known != null ? known : types.require(person, request.getTypeId());
			// Before the claim: a refusal is not a decision, and a request refused for a balance
			// has to stay exactly where it was.
			assertBalanceCovers(person, type, request);
		}
		TimeOffRequest.Status before = request.getStatus();
		int steps = request.getHistory() == null ? 0 : request.getHistory().size();
		request.setDecidedBy(decider.getId());
		request.setDecidedAt(clock.instant());
		request.setDecisionNote(note(note));
		TimeOffRequest saved = transition(request, decider, target,
				note, target == TimeOffRequest.Status.APPROVED
						? AuditAction.TIME_OFF_REQUEST_APPROVED
						: AuditAction.TIME_OFF_REQUEST_REJECTED);
		if (target == TimeOffRequest.Status.APPROVED) {
			try {
				String absenceId = absences.enter(decider, person, type.getId(), saved.getFrom(), saved.getTo(),
						isHalfDay(saved), saved.getNote());
				saved.setTimeOffId(absenceId);
				if (type.countsAgainstBalance() && saved.milliDays() > 0) {
					saved.setLedgerId(book(decider, person, type, saved.getFrom(), -saved.milliDays(),
							saved.getId(), null).getId());
				}
				saved = requests.save(saved);
			}
			catch (RuntimeException failed) {
				unclaim(saved, before, steps);
				throw failed;
			}
		}
		if (automatic) {
			notifications.notifyTimeOffAutoApproved(person, "/absences/requests");
			notifySubstitute(saved, person);
		} else {
			notifications.notifyTimeOffDecided(person,
					target == TimeOffRequest.Status.APPROVED
							? NotificationService.TimeOffEvent.APPROVED
							: NotificationService.TimeOffEvent.REJECTED,
					"/absences/requests");
			if (target == TimeOffRequest.Status.APPROVED) {
				notifySubstitute(saved, person);
			}
		}
		return saved;
	}

	/**
	 * Puts a claim back when what it was claimed for could not be written.
	 *
	 * <p>The step is dropped rather than followed by a second one: it did not happen, and a history
	 * that said "approved, then un-approved" would invite the question of what was approved. Best
	 * effort — if even this write fails there is nothing further to try, and a request stuck as
	 * approved with no absence behind it is visible to the person and to whoever decided it.
	 */
	private void unclaim(TimeOffRequest request, TimeOffRequest.Status before, int steps) {
		try {
			request.setStatus(before);
			request.setDecidedBy(null);
			request.setDecidedAt(null);
			request.setDecisionNote(null);
			if (request.getHistory() != null) {
				while (request.getHistory().size() > steps) {
					request.getHistory().removeLast();
				}
			}
			request.setUpdatedAt(clock.instant());
			requests.save(request);
		}
		catch (RuntimeException lost) {
			log.warn("[timeoff] could not put back the claim on request {}: {}",
					request.getId(), lost.toString());
		}
	}

	/** Moves the status, records the step, and answers a race with 409 rather than a second write. */
	private TimeOffRequest transition(TimeOffRequest request, User actor, TimeOffRequest.Status target,
			String note, AuditAction action) {
		TimeOffRequest.Status before = request.getStatus();
		request.setStatus(target);
		request.record(TimeOffRequest.Event.builder()
				.at(clock.instant()).by(actor.getId()).from(before).to(target).note(note(note)).build());
		request.setUpdatedAt(clock.instant());
		TimeOffRequest saved;
		try {
			saved = requests.save(request);
		} catch (OptimisticLockingFailureException decidedConcurrently) {
			// Two people pressed the same button. One of them booked; the other must not book
			// again, or a week off would cost two weeks of balance.
			throw ApiException.conflict("error.timeOff.requestChangedMeanwhile");
		}
		audited(action, saved, actor);
		return saved;
	}

	/** Gives [milliDays] back to a balance as a {@code RETURNED} row, never by deleting a booking. */
	private void returnDays(TimeOffRequest request, User person, User actor, int milliDays) {
		if (request.getLedgerId() == null || milliDays <= 0) {
			return;
		}
		TimeOffType type = types.require(person, request.getTypeId());
		if (!type.countsAgainstBalance()) {
			return;
		}
		book(actor, person, type, request.getFrom(), milliDays, request.getId(), request.getLedgerId());
		request.setLedgerId(null);
	}

	/**
	 * § 9 BUrlG: hands back the days of approved leave that [from]–[to] of sickness fell on.
	 *
	 * <p>The leave is shortened rather than erased — the days before the person fell ill were
	 * leave and stay leave. Whoever decided it sees that it got shorter and never why: a decider
	 * learning "shortened because they were ill" would be learning a health fact through the back
	 * door (R10, R11).
	 */
	private int returnOverlappedLeave(User person, LocalDate from, LocalDate to) {
		List<TimeOffRequest> approved = requests.findByUserIdAndStatusAndToGreaterThanEqualAndFromLessThanEqual(
				person.getId(), TimeOffRequest.Status.APPROVED, from, to);
		int returned = 0;
		// Normally none or one. Bounded anyway: this runs inside a report nothing may refuse, and
		// a loop whose length comes from stored data is a loop somebody can make long.
		for (TimeOffRequest leave : approved.stream().limit(OVERLAP_MAX).toList()) {
			try {
				returned += giveBack(leave, person, from, to);
			}
			catch (RuntimeException failed) {
				// § 5 EFZG knows a notification, not a permission: there is no path through here
				// that may answer no. A give-back that could not be written is a balance somebody
				// has to correct by hand, which is a smaller wrong than a refused sick report.
				log.warn("[timeoff] could not give back leave {} for a sick report: {}",
						leave.getId(), failed.toString());
			}
		}
		return returned;
	}

	/** One leave the sickness fell on: shortened, its days returned, everybody it concerns told. */
	private int giveBack(TimeOffRequest leave, User person, LocalDate from, LocalDate to) {
		LocalDate overlapFrom = leave.getFrom().isBefore(from) ? from : leave.getFrom();
		LocalDate overlapTo = leave.getTo().isAfter(to) ? to : leave.getTo();
		TimeOffSpan.Result eaten = workingDays.of(person.getId(), overlapFrom, overlapTo,
				TimeOffSpan.DAY, TimeOffSpan.DAY);
		if (eaten.milliDays() <= 0) {
			return 0;
		}
		String absenceId = leave.getTimeOffId();
		boolean shorten = overlapFrom.isAfter(leave.getFrom());
		String ledgerId = leave.getLedgerId();
		// The claim first, as everywhere else: the document is written under its version guard
		// before anything is booked, so a second pass over the same leave finds nothing left to
		// give back rather than crediting the days twice.
		if (shorten) {
			leave.setTo(overlapFrom.minusDays(1));
		} else {
			leave.setTimeOffId(null);
		}
		leave.setLedgerId(null);
		leave.record(TimeOffRequest.Event.builder()
				.at(clock.instant()).from(TimeOffRequest.Status.APPROVED)
				.to(TimeOffRequest.Status.APPROVED).build());
		leave.setUpdatedAt(clock.instant());
		requests.save(leave);

		if (ledgerId != null) {
			TimeOffType type = types.require(person, leave.getTypeId());
			if (type.countsAgainstBalance()) {
				book(person, person, type, leave.getFrom(), eaten.milliDays(), leave.getId(), ledgerId);
			}
		}
		if (shorten) {
			absences.endOn(person, absenceId, overlapFrom.minusDays(1));
		} else {
			absences.remove(person, absenceId);
		}
		// The deciders learn that the leave got shorter and never why: "shortened because they
		// were ill" would hand a lead a health fact through a side door (R10, R11).
		notifications.notifyTimeOffShortened(person, new LinkedHashSet<>(leave.getApproverIds()),
				person.getDisplayName(), "/absences/requests");
		return eaten.milliDays();
	}

	private TimeOffLedgerEntry book(User actor, User person, TimeOffType type, LocalDate on, int milliDays,
			String refId, String reversalOf) {
		return balances.book(TimeOffLedgerEntry.builder()
				.userId(person.getId())
				.typeId(type.getId())
				// The leave year the span starts in. A span that crosses New Year belongs to the
				// year it was taken from, which is the year it was granted against — splitting it
				// would make one absence two balances and neither of them answerable.
				.year(on.getYear())
				.kind(milliDays < 0 ? TimeOffLedgerEntry.Kind.BOOKED : TimeOffLedgerEntry.Kind.RETURNED)
				.milliDays(milliDays)
				.effectiveOn(on)
				.refId(refId)
				.reversalOf(reversalOf)
				.actorId(actor.getId())
				.build());
	}

	// ------------------------------------------------------------------ rules

	private TimeOffSpan.Result span(User person, TimeOffType type, Draft draft) {
		if (draft.from() == null) {
			throw ApiException.badRequest("error.timeOff.spanInvalid");
		}
		LocalDate to = draft.to() == null ? draft.from() : draft.to();
		TimeOffSpan.Result span = workingDays.of(person.getId(), draft.from(), to,
				portion(draft.first()), portion(draft.last()));
		if (span.milliDays() <= 0) {
			// Every day of it is a weekend, a holiday or a day this person does not work. Not a
			// technicality: there is no leave to grant, so there is nothing to decide.
			throw ApiException.badRequest("error.timeOff.spanHasNoWorkingDays");
		}
		return span;
	}

	private void assertWithinTypeRules(TimeOffType type, TimeOffSpan.Result span, Draft draft) {
		boolean partialEdges = portion(draft.first()) != TimeOffSpan.DAY || portion(draft.last()) != TimeOffSpan.DAY;
		boolean halves = portion(draft.first()) == TimeOffSpan.DAY / 2 || portion(draft.last()) == TimeOffSpan.DAY / 2;
		if (partialEdges && !halves && !Boolean.TRUE.equals(type.getFractionAllowed())) {
			throw ApiException.badRequest("error.timeOff.fractionNotAllowed");
		}
		if (halves && !Boolean.TRUE.equals(type.getHalfDaysAllowed())) {
			throw ApiException.badRequest("error.timeOff.halfDayNotAllowed");
		}
		Integer max = type.getMaxConsecutiveDays();
		if (max != null && max > 0 && span.workingDays() > max) {
			// One of the two hard rules: the type says so in as many words, so refusing it is
			// carrying out the operator's decision rather than making one.
			throw ApiException.badRequest("error.timeOff.tooManyConsecutiveDays", max);
		}
	}

	/**
	 * The other hard rule: a balance the type will not let go negative.
	 *
	 * <p>Checked when the decision is made rather than when the request is filed, because that is
	 * when it has to be true — a grant may well arrive in between, and refusing at submission would
	 * turn a question of timing into a wall.
	 */
	private void assertBalanceCovers(User person, TimeOffType type, TimeOffRequest request) {
		if (!type.countsAgainstBalance() || type.isUnlimited()) {
			return;
		}
		int after = balances.remainingMilliDays(person.getId(), type.getId(), request.getFrom().getYear())
				- request.milliDays();
		if (after >= 0) {
			return;
		}
		if (!type.negativeBalanceAllowed()) {
			throw TimeOffRefusal.balanceExceeded();
		}
		// A type that allows a negative balance may still name how far. Null is "no floor beyond
		// zero" only for a type that does not allow one at all; here it means no floor.
		Integer floor = type.getNegativeLimitMilliDays();
		if (floor != null && after < -floor) {
			throw TimeOffRefusal.balanceExceeded();
		}
	}

	private boolean balanceShort(User person, TimeOffType type, LocalDate from, int milliDays) {
		if (!type.countsAgainstBalance() || type.isUnlimited()) {
			return false;
		}
		return balances.remainingMilliDays(person.getId(), type.getId(), from.getYear()) - milliDays < 0;
	}

	private boolean shortNotice(TimeOffType type, LocalDate from) {
		Integer notice = type.getMinNoticeDays();
		if (notice == null || notice <= 0 || from == null) {
			return false;
		}
		return LocalDate.now(clock).plusDays(notice).isAfter(from);
	}

	private TimeOffType sickType(User person, String typeId) {
		if (typeId != null && !typeId.isBlank()) {
			TimeOffType named = types.require(person, typeId);
			if (named.getKind() != TimeOffType.Kind.SICK) {
				throw ApiException.badRequest("error.timeOff.notASickType");
			}
			return named;
		}
		return types.byKey(TimeOffType.SYSTEM_SICK)
				.orElseThrow(() -> ApiException.badRequest("error.timeOff.noSickType"));
	}

	// ------------------------------------------------------------------ small things

	private TimeOffRequest decidable(String id, User decider) {
		TimeOffRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("timeOffRequest"));
		if (request.getUserId().equals(decider.getId())) {
			// The second of the two guards. The first struck them out of their own audience when
			// the request was filed; this one holds even if that list were ever wrong.
			throw ApiException.forbidden("error.timeOff.decideOwn");
		}
		boolean mayDecide = access.isKeeper(decider) || request.getApproverIds().contains(decider.getId());
		if (!mayDecide) {
			throw ApiException.notFound("timeOffRequest");
		}
		if (!request.getStatus().open()) {
			throw ApiException.conflict("error.timeOff.requestDecided");
		}
		return request;
	}

	/**
	 * Whether [viewer] may read this request: the person, whoever may decide it, or a keeper.
	 *
	 * <p><b>Not the stand-in.</b> They are told the name and the dates, which is the whole of what
	 * standing in for somebody requires (see {@code NotificationService.notifyTimeOffSubstitute}).
	 * The document carries the note the person wrote and the sentence a rejection gave, and being
	 * named on a form is not a reason to be handed either.
	 */
	private boolean concerns(TimeOffRequest request, User viewer) {
		return request.getUserId().equals(viewer.getId())
				|| request.getApproverIds().contains(viewer.getId())
				|| access.isKeeper(viewer);
	}

	private User personOf(TimeOffRequest request) {
		return users.findById(request.getUserId()).orElseThrow(() -> ApiException.notFound("user"));
	}

	/**
	 * Everybody a cancellation concerns except whoever cancelled it — and except the person it is
	 * about, who is told in their own words.
	 */
	private Set<String> othersOf(TimeOffRequest request, User actor) {
		Set<String> ids = new LinkedHashSet<>(request.getApproverIds());
		if (request.getSubstituteId() != null) {
			ids.add(request.getSubstituteId());
		}
		ids.remove(actor.getId());
		ids.remove(request.getUserId());
		return ids;
	}

	private void notifySubstitute(TimeOffRequest request, User person) {
		if (request.getSubstituteId() == null) {
			return;
		}
		users.findById(request.getSubstituteId()).ifPresent(stand ->
				notifications.notifyTimeOffSubstitute(stand, person.getDisplayName(), request.getFrom(),
						request.getTo(), "/absences/requests"));
	}

	private String substitute(User person, String substituteId) {
		if (substituteId == null || substituteId.isBlank() || substituteId.equals(person.getId())) {
			return null;
		}
		return users.findById(substituteId).map(User::getId)
				.orElseThrow(() -> ApiException.badRequest("error.timeOff.substituteUnknown"));
	}

	private static int portion(Integer milliDays) {
		if (milliDays == null) {
			return TimeOffSpan.DAY;
		}
		if (milliDays <= 0 || milliDays > TimeOffSpan.DAY) {
			throw ApiException.badRequest("error.timeOff.portionInvalid");
		}
		return milliDays;
	}

	/** A one-day request for half a day is the half day the old calendar already understood. */
	private static boolean isHalfDay(TimeOffRequest request) {
		return request.getFrom().equals(request.getTo())
				&& request.getFirstDayMilliDays() != null
				&& request.getFirstDayMilliDays() == TimeOffSpan.DAY / 2;
	}

	private static String note(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String clean = text.strip();
		return clean.length() > TimeOffRequest.NOTE_MAX ? clean.substring(0, TimeOffRequest.NOTE_MAX) : clean;
	}

	private static Pageable pageOf(int page, int size) {
		int capped = Math.clamp(size, 1, PAGE_MAX);
		return PageRequest.of(Math.max(0, page), capped, TimeOffRequestRepository.NEWEST_FIRST);
	}

	/**
	 * Records the step.
	 *
	 * <p>The type, the span and the amount — never the note and never the decision's reason. A
	 * reason may name an illness or a recognised disability (§ 208 SGB IX), and in the audit log it
	 * would outlive the account, survive the module being switched off, and be readable by every
	 * administrator. The sentence stays on the request, where it disappears with it (Art. 9 DSGVO).
	 */
	private void audited(AuditAction action, TimeOffRequest request, User actor) {
		audit.event(action).actor(actor)
				.target(request.getId(), request.getFrom() + " – " + request.getTo())
				.meta("user", request.getUserId())
				.meta("from", String.valueOf(request.getFrom()))
				.meta("to", String.valueOf(request.getTo()))
				.meta("milliDays", String.valueOf(request.milliDays()))
				.log();
	}
}
