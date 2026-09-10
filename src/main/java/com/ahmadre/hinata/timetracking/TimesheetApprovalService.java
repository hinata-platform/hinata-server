package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handing a period in, and signing it off.
 *
 * <p>A submission is a person's statement that their record of a span is
 * complete; an approval is somebody else's that they accept it. Both are human
 * acts and neither is ever automatic — Art. 22 DSGVO aside, an automatic
 * approval would make the whole mechanism a formality, and an automatic
 * <em>submission</em> would freeze a period its owner never said they had
 * finished.
 *
 * <p>Two invariants run through everything here:
 *
 * <ul>
 * <li><b>Nobody approves their own time.</b> A lead who submits their own period
 * needs a second lead or an administrator. An approval is a check, and a check
 * you perform on yourself is a signature.</li>
 * <li><b>A frozen period is never a dead end.</b> Rejecting sends it back with a
 * reason, reopening takes an approved one back, and a person whose period is
 * already signed off can ask for a correction. Working time is personal data and
 * Art. 16 DSGVO gives a right to have it put right; a mechanism with no way back
 * would be the one thing that right does not permit.</li>
 * </ul>
 *
 * <p>The freeze itself is not enforced here. It lives in {@link TimeLocks},
 * which every write to an entry passes through, so a rule added there holds for
 * the app, for MCP and for a smart commit without any of them remembering to
 * ask.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimesheetApprovalService {

	/** Largest page of approvals handed out, mine or inbox. */
	public static final int PAGE_MAX = 100;

	/** Longest window {@code GET /approvals/periods} will enumerate. */
	public static final int MAX_WINDOW_DAYS = 400;

	private final TimesheetApprovalRepository approvals;
	private final TimeTrackingService entries;
	private final TimeLocks locks;
	private final ProjectTimeSettingsRepository projectSettings;
	private final TimeTrackingSettings policy;
	private final ProjectService projects;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final Clock clock;

	// --- what a client sees ----------------------------------------------------

	/** One project's standing inside one period, for the timesheet's status row. */
	public record ProjectStatus(String projectId, String projectKey, String projectName,
			TimesheetApproval.Status status, String approvalId, int minutes, boolean required,
			String note) {
	}

	/**
	 * One period and where each of the caller's projects stands in it.
	 *
	 * <p>The label is the server's, not a client's: "March 2026", "CW 12",
	 * "1–15 March" are renderings of a rhythm, and a client that derived them from
	 * a type name would be the second implementation of the arithmetic this stage
	 * exists to keep in one place. The client formats the two dates in the
	 * reader's locale and names the type; nothing more.
	 */
	public record PeriodView(LocalDate start, LocalDate end, TimePolicy.ApprovalPeriod type,
			List<ProjectStatus> projects) {
	}

	// --- reads ------------------------------------------------------------------

	/**
	 * Whether this instance submits timesheets at all.
	 *
	 * <p>Both switches, because the approval routes live inside the module: with
	 * the module off there is no time tracking to approve, and with approvals off
	 * there is time tracking and nothing to hand in.
	 */
	public boolean enabled() {
		return policy.advancedEnabled() && policy.approvalsEnabled();
	}

	/** 404 — not 403 — while the policy is off, exactly as the module's own gate does. */
	public void assertEnabled() {
		if (!enabled()) {
			throw ApiException.notFound("timesheetApproval");
		}
	}

	/**
	 * The rhythm in force for one project: its own override, field by field, over
	 * the instance policy.
	 *
	 * <p>Field by field rather than all-or-nothing, the same way the instance
	 * policy resolves against the environment: a project that overrides only the
	 * type keeps the anchor the instance supplies, and an operator who later moves
	 * that anchor moves it for the project too.
	 */
	public TimeTrackingSettings.ApprovalPeriod periodPolicy(String projectId) {
		TimeTrackingSettings.ApprovalPeriod instance = policy.approvalPeriod();
		if (projectId == null) {
			return instance;
		}
		ProjectTimeSettings.ApprovalPeriod override = projectSettings.findByProjectId(projectId)
				.map(ProjectTimeSettings::getApprovalPeriod)
				.orElse(null);
		if (override == null) {
			return instance;
		}
		return new TimeTrackingSettings.ApprovalPeriod(
				override.getType() != null ? override.getType() : instance.type(),
				override.getWeekStartsOn() != null ? override.getWeekStartsOn()
						: instance.weekStartsOn(),
				override.getAnchorDate() != null ? override.getAnchorDate() : instance.anchorDate(),
				override.getDays() != null ? override.getDays() : instance.days());
	}

	/** Whether this project's periods are meant to be handed in; default yes. */
	public boolean approvalRequired(String projectId) {
		if (projectId == null) {
			return false;
		}
		return projectSettings.findByProjectId(projectId)
				.map(ProjectTimeSettings::getApprovalRequired)
				.orElse(Boolean.TRUE);
	}

	/**
	 * The periods overlapping a window, with the caller's standing in each.
	 *
	 * <p>Always about the caller's own time. An approver reads somebody else's
	 * period through the inbox, where the route says whose it is and the rule is
	 * stated once; a "whose time?" parameter here would be a second place for that
	 * rule to be got wrong.
	 *
	 * <p>Under a FREE rhythm there is no grid, so the periods are the ones that
	 * exist: what the person has already handed in. That is what the client needs
	 * to warn about an overlapping pick before the server refuses it, and it comes
	 * from the same route rather than a second one.
	 */
	public List<PeriodView> periods(LocalDate from, LocalDate to, String projectId, User user) {
		assertEnabled();
		if (from == null || to == null || to.isBefore(from)) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
		if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
			throw ApiException.badRequest("error.time.rangeTooLong");
		}
		// Per day, not per window: one window can hold several periods, and each
		// needs its own figure. A single window total shown against three months
		// would give all three the same number.
		Map<LocalDate, Map<String, Integer>> perDay =
				entries.minutesPerProjectAndDay(user.getId(), from, to, projectId);
		// The projects the caller booked time against in the window, plus the one
		// they asked about if they named it. A project with no hours in the period
		// has nothing to submit, so it is not offered.
		Set<String> projectIds = new LinkedHashSet<>();
		perDay.values().forEach(byProject -> projectIds.addAll(byProject.keySet()));
		projectIds.remove(null);
		if (projectId != null) {
			projectIds.add(projectId);
		}
		List<TimesheetApproval> existing = projectIds.isEmpty() ? List.of()
				: approvals.findByUserIdAndProjectIdInAndPeriodEndGreaterThanEqualAndPeriodStartLessThanEqual(
						user.getId(), projectIds, from, to);
		Map<String, Project> named = namesOf(projectIds, user);
		// One rhythm per answer, because the switcher it feeds has one set of
		// arrows. Naming a project asks about that project's rhythm — which is
		// what the filtered timesheet does; asking about everything asks about the
		// instance's. A list that mixed two grids would be a list of periods that
		// do not line up, and no client could draw it.
		TimeTrackingSettings.ApprovalPeriod gridPolicy = projectId != null
				? periodPolicy(projectId)
				: policy.approvalPeriod();
		List<ApprovalPeriods.Period> grid = ApprovalPeriods.hasGrid(gridPolicy)
				? ApprovalPeriods.periodsIn(from, to, gridPolicy)
				: freePeriods(existing);
		List<PeriodView> views = new ArrayList<>(grid.size());
		for (ApprovalPeriods.Period period : grid) {
			Map<String, Integer> minutes = minutesIn(perDay, period);
			List<ProjectStatus> statuses = new ArrayList<>();
			for (String id : projectIds) {
				TimesheetApproval approval = existing.stream()
						.filter(row -> id.equals(row.getProjectId()))
						.filter(row -> row.getPeriodStart() != null
								&& !row.getPeriodStart().isAfter(period.end())
								&& row.getPeriodEnd() != null
								&& !row.getPeriodEnd().isBefore(period.start()))
						.findFirst()
						.orElse(null);
				Project project = named.get(id);
				statuses.add(new ProjectStatus(id,
						project == null ? null : project.getKey(),
						project == null ? null : project.getName(),
						approval == null ? null : approval.getStatus(),
						approval == null ? null : approval.getId(),
						minutes.getOrDefault(id, 0),
						approvalRequired(id),
						approval == null ? null : approval.getNote()));
			}
			statuses.sort(Comparator.comparing(ProjectStatus::projectKey,
					Comparator.nullsLast(Comparator.naturalOrder())));
			views.add(new PeriodView(period.start(), period.end(), period.type(), statuses));
		}
		return views;
	}

	/** This period's share of a per-day tally, folded back to one figure per project. */
	private static Map<String, Integer> minutesIn(
			Map<LocalDate, Map<String, Integer>> perDay, ApprovalPeriods.Period period) {
		Map<String, Integer> minutes = new LinkedHashMap<>();
		perDay.forEach((day, byProject) -> {
			if (period.contains(day)) {
				byProject.forEach((project, booked) -> minutes.merge(project, booked, Integer::sum));
			}
		});
		return minutes;
	}

	/**
	 * A FREE rhythm's "periods": the spans that have been handed in.
	 *
	 * <p>Deduplicated on the dates, because the same span submitted for two
	 * projects is one period with two statuses — which is exactly the shape a grid
	 * period has.
	 */
	private static List<ApprovalPeriods.Period> freePeriods(List<TimesheetApproval> existing) {
		return existing.stream()
				.filter(row -> row.getPeriodStart() != null && row.getPeriodEnd() != null)
				.map(row -> new ApprovalPeriods.Period(row.getPeriodStart(), row.getPeriodEnd(),
						TimePolicy.ApprovalPeriod.FREE))
				.distinct()
				.sorted(Comparator.comparing(ApprovalPeriods.Period::start))
				.toList();
	}

	/** The caller's own submissions, newest first. */
	public Page<TimesheetApproval> mine(TimesheetApproval.Status status, int page, int size,
			User user) {
		assertEnabled();
		Pageable pageable = pageOf(page, size);
		return status == null
				? approvals.findByUserId(user.getId(), pageable)
				: approvals.findByUserIdAndStatus(user.getId(), status, pageable);
	}

	/**
	 * What is waiting for this approver, newest submission first.
	 *
	 * <p>Only the projects they lead. An administrator sees every project, which
	 * is the one case where the project filter is dropped rather than widened to a
	 * list — an instance with two thousand projects would otherwise send two
	 * thousand ids into an {@code $in}.
	 */
	public Page<TimesheetApproval> inbox(TimesheetApproval.Status status, int page, int size,
			User user) {
		assertEnabled();
		Pageable pageable = pageOf(page, size);
		if (user.isAdmin()) {
			return status == null
					? approvals.findAll(pageable)
					: approvals.findByStatus(status, pageable);
		}
		Collection<String> led = ledProjectIds(user);
		if (led.isEmpty()) {
			return Page.empty(pageable);
		}
		return status == null
				? approvals.findByProjectIdIn(led, pageable)
				: approvals.findByProjectIdInAndStatus(led, status, pageable);
	}

	/**
	 * One submission, for its owner or for somebody who may decide it.
	 *
	 * <p>404 rather than 403 for a stranger. A 403 would confirm that a
	 * submission with that id exists for some colleague and some project, which is
	 * the kind of answer a person can walk an id space with.
	 */
	public TimesheetApproval get(String id, User user) {
		assertEnabled();
		TimesheetApproval approval = approvals.findById(id)
				.orElseThrow(() -> ApiException.notFound("timesheetApproval"));
		if (!isOwner(approval, user) && !canDecide(approval, user)) {
			throw ApiException.notFound("timesheetApproval");
		}
		return approval;
	}

	/** The entries a submission covers, paged — the approver's actual reading material. */
	public Page<WorkItem> entriesOf(String id, int page, int size, User user) {
		TimesheetApproval approval = get(id, user);
		return entries.entriesOfPeriod(approval.getUserId(), approval.getProjectId(),
				approval.getPeriodStart(), approval.getPeriodEnd(), page, size);
	}

	// --- writes -----------------------------------------------------------------

	/**
	 * Hands a span in — one submission per project that has hours in it.
	 *
	 * <p>Per project, because that is who approves: a lead signs off the time
	 * booked against their project and has no business seeing the rest of
	 * somebody's week. Entries with no project are never part of a submission;
	 * they are private, belong to no lead, and have nobody to approve them.
	 *
	 * <p>A period that was rejected or withdrawn is handed in <em>again</em> rather
	 * than a second row being written. The unique index would refuse the second
	 * row anyway, and that is the point: "is this period frozen" has to be a
	 * question about one document, not about the newest of several.
	 */
	public List<TimesheetApproval> submit(LocalDate periodStart, LocalDate periodEnd,
			List<String> requestedProjects, User user) {
		assertEnabled();
		if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
		if (periodStart.plusDays(TimePolicy.PERIOD_MAX_DAYS - 1L).isBefore(periodEnd)) {
			throw ApiException.badRequest("error.time.periodTooLong");
		}
		Map<String, Integer> minutes =
				entries.minutesPerProject(user.getId(), periodStart, periodEnd, null);
		Set<String> candidates = new LinkedHashSet<>(minutes.keySet());
		candidates.remove(null);
		if (requestedProjects != null && !requestedProjects.isEmpty()) {
			candidates.retainAll(new HashSet<>(requestedProjects));
		}
		if (candidates.isEmpty()) {
			// Nothing to hand in. A 400 rather than an empty 200: the client
			// offered the action, so saying "there are no hours in this period"
			// is an answer, and a silent success would look like a submission
			// that then cannot be found.
			throw ApiException.badRequest("error.time.periodEmpty");
		}
		List<TimesheetApproval> submitted = new ArrayList<>(candidates.size());
		for (String projectId : candidates) {
			// The grid is the *project's*, because the project is who approves —
			// one may run monthly while another closes per quarter.
			TimeTrackingSettings.ApprovalPeriod grid = periodPolicy(projectId);
			if (!ApprovalPeriods.matchesGrid(periodStart, periodEnd, grid)) {
				throw ApiException.badRequest("error.time.periodNotOnGrid");
			}
			submitted.add(submitOne(projectId, periodStart, periodEnd, grid,
					minutes.getOrDefault(projectId, 0), user));
		}
		notifyApprovers(submitted, user);
		return submitted;
	}

	private TimesheetApproval submitOne(String projectId, LocalDate periodStart,
			LocalDate periodEnd, TimeTrackingSettings.ApprovalPeriod grid, int minutes, User user) {
		TimePolicy.ApprovalPeriod type = grid.type();
		TimesheetApproval existing = approvals
				.findByUserIdAndProjectIdAndPeriodStart(user.getId(), projectId, periodStart)
				.orElse(null);
		if (existing != null && existing.freezes()) {
			throw ApiException.conflict("error.time.periodAlreadySubmitted");
		}
		assertNoOverlap(user.getId(), projectId, periodStart, periodEnd,
				existing == null ? null : existing.getId());
		TimesheetApproval approval = existing != null ? existing
				: TimesheetApproval.builder()
						.userId(user.getId())
						.projectId(projectId)
						.periodStart(periodStart)
						.build();
		TimesheetApproval.Status from = existing == null ? null : existing.getStatus();
		approval.setPeriodEnd(periodEnd);
		approval.setPeriodType(type);
		approval.setStatus(TimesheetApproval.Status.SUBMITTED);
		approval.setTotalMinutes(minutes);
		approval.setSubmittedAt(clock.instant());
		approval.setUpdatedAt(clock.instant());
		// The previous decision's reason and author belong to the previous
		// decision. Leaving a rejection note on a freshly handed-in period would
		// have the inbox show somebody's own words back as the current state.
		approval.setNote(null);
		approval.setDecidedBy(null);
		approval.setDecidedAt(null);
		approval.record(TimesheetApproval.Event.builder()
				.at(clock.instant()).by(user.getId())
				.from(from).to(TimesheetApproval.Status.SUBMITTED).build());
		TimesheetApproval saved = save(approval);
		// Only after the row exists, and only after the overlap re-check below
		// has let it stand: a refused submission must leave nothing behind, not
		// even an audit record of a thing that never happened.
		audited(AuditAction.TIMESHEET_SUBMITTED, saved, user, null);
		return saved;
	}

	/**
	 * Inserts, then looks again.
	 *
	 * <p>The unique index covers two submissions of the same period start, which
	 * is every rhythm with a grid. It cannot cover two <em>overlapping</em> FREE
	 * spans: "no two ranges intersect" is not something a Mongo index can express.
	 * So the check runs before the write and again after it, and the later of the
	 * two writers stands down — compared on {@code submittedAt}, and on the id
	 * when those are equal, so the contest has exactly one winner whatever order
	 * the two arrive in.
	 */
	private TimesheetApproval save(TimesheetApproval approval) {
		TimesheetApproval saved;
		try {
			saved = approvals.save(approval);
		}
		catch (DuplicateKeyException submittedConcurrently) {
			throw ApiException.conflict("error.time.periodAlreadySubmitted");
		}
		TimesheetApproval winner = overlapping(saved.getUserId(), saved.getProjectId(),
				saved.getPeriodStart(), saved.getPeriodEnd(), saved.getId()).stream()
				.min(Comparator.comparing(TimesheetApproval::getSubmittedAt,
								Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparing(TimesheetApproval::getId))
				.orElse(null);
		if (winner != null && beats(winner, saved)) {
			approvals.deleteById(saved.getId());
			throw ApiException.conflict("error.time.periodOverlaps");
		}
		return saved;
	}

	private static boolean beats(TimesheetApproval winner, TimesheetApproval mine) {
		if (winner.getSubmittedAt() == null || mine.getSubmittedAt() == null) {
			return winner.getId().compareTo(mine.getId()) < 0;
		}
		int byTime = winner.getSubmittedAt().compareTo(mine.getSubmittedAt());
		return byTime != 0 ? byTime < 0 : winner.getId().compareTo(mine.getId()) < 0;
	}

	private void assertNoOverlap(String userId, String projectId, LocalDate start, LocalDate end,
			String selfId) {
		if (!overlapping(userId, projectId, start, end, selfId).isEmpty()) {
			throw ApiException.conflict("error.time.periodOverlaps");
		}
	}

	/** Freezing submissions of this person and project that share a day with the span. */
	private List<TimesheetApproval> overlapping(String userId, String projectId, LocalDate start,
			LocalDate end, String selfId) {
		return approvals.findByUserIdAndProjectIdAndStatusInAndPeriodEndGreaterThanEqual(
						userId, projectId,
						List.of(TimesheetApproval.Status.SUBMITTED,
								TimesheetApproval.Status.APPROVED),
						start)
				.stream()
				.filter(row -> !row.getId().equals(selfId))
				.filter(row -> row.getPeriodStart() != null && !row.getPeriodStart().isAfter(end))
				.toList();
	}

	/** Takes a submission back. Only its owner, and only while nobody has decided it. */
	public TimesheetApproval withdraw(String id, User user) {
		assertEnabled();
		TimesheetApproval approval = approvals.findById(id)
				.orElseThrow(() -> ApiException.notFound("timesheetApproval"));
		if (!isOwner(approval, user)) {
			throw ApiException.notFound("timesheetApproval");
		}
		if (approval.getStatus() != TimesheetApproval.Status.SUBMITTED) {
			throw ApiException.conflict("error.time.approvalNotPending");
		}
		return transition(approval, TimesheetApproval.Status.WITHDRAWN, null, user,
				AuditAction.TIMESHEET_WITHDRAWN);
	}

	/**
	 * Signs a period off, or sends it back.
	 *
	 * <p>A rejection without a reason is refused. The person on the other end has
	 * to be able to act on it, and "no" on its own is not something anybody can
	 * act on; it is also the part a works council will ask about.
	 */
	public TimesheetApproval decide(String id, TimesheetApproval.Status target, String note,
			User user) {
		assertEnabled();
		if (target != TimesheetApproval.Status.APPROVED
				&& target != TimesheetApproval.Status.REJECTED) {
			throw ApiException.badRequest("error.time.approvalDecisionInvalid");
		}
		TimesheetApproval approval = approvals.findById(id)
				.orElseThrow(() -> ApiException.notFound("timesheetApproval"));
		assertMayDecide(approval, user);
		if (approval.getStatus() != TimesheetApproval.Status.SUBMITTED) {
			throw ApiException.conflict("error.time.approvalNotPending");
		}
		String reason = trimmedNote(note);
		if (target == TimesheetApproval.Status.REJECTED && reason == null) {
			throw ApiException.badRequest("error.time.approvalNoteRequired");
		}
		TimesheetApproval saved = transition(approval, target, reason, user,
				target == TimesheetApproval.Status.APPROVED
						? AuditAction.TIMESHEET_APPROVED
						: AuditAction.TIMESHEET_REJECTED);
		notifyOwner(saved, target == TimesheetApproval.Status.APPROVED
				? NotificationService.TimesheetEvent.APPROVED
				: NotificationService.TimesheetEvent.REJECTED, user);
		return saved;
	}

	/**
	 * Takes an approved period back so it can be corrected, with a reason.
	 *
	 * <p>This is the documented way back from the second kind of immutability, and
	 * it is the reason the first kind got one too (the lock exceptions). A freeze
	 * without a remedy would collide with Art. 16 DSGVO the moment somebody's
	 * hours were recorded wrongly.
	 */
	public TimesheetApproval reopen(String id, String note, User user) {
		assertEnabled();
		TimesheetApproval approval = approvals.findById(id)
				.orElseThrow(() -> ApiException.notFound("timesheetApproval"));
		assertMayDecide(approval, user);
		if (approval.getStatus() != TimesheetApproval.Status.APPROVED) {
			throw ApiException.conflict("error.time.approvalNotApproved");
		}
		String reason = trimmedNote(note);
		if (reason == null) {
			throw ApiException.badRequest("error.time.approvalNoteRequired");
		}
		TimesheetApproval saved = transition(approval, TimesheetApproval.Status.REJECTED, reason,
				user, AuditAction.TIMESHEET_REOPENED);
		notifyOwner(saved, NotificationService.TimesheetEvent.REOPENED, user);
		return saved;
	}

	private TimesheetApproval transition(TimesheetApproval approval,
			TimesheetApproval.Status target, String note, User user, AuditAction action) {
		TimesheetApproval.Status from = approval.getStatus();
		approval.setStatus(target);
		approval.setNote(note);
		approval.setUpdatedAt(clock.instant());
		if (target != TimesheetApproval.Status.WITHDRAWN) {
			approval.setDecidedBy(user.getId());
			approval.setDecidedAt(clock.instant());
		}
		approval.record(TimesheetApproval.Event.builder()
				.at(clock.instant()).by(user.getId()).from(from).to(target).note(note).build());
		TimesheetApproval saved = approvals.save(approval);
		audited(action, saved, user, note);
		return saved;
	}

	/**
	 * Asks for a frozen entry to be opened — the person's own Art.-16 route.
	 *
	 * <p>It changes nothing. That is deliberate: the request is addressed to
	 * whoever can lift the freeze, and a mechanism that let the asking itself
	 * unfreeze anything would be the freeze with an extra step. What it does is
	 * make the ask visible and recorded, so it cannot be lost in a chat.
	 *
	 * <p>An entry that is <em>not</em> frozen is refused. Without that the route
	 * would be a way to notify a project's leads about any entry at all.
	 */
	public void requestCorrection(String workItemId, String note, User user) {
		assertEnabled();
		WorkItem item = entries.requireOwn(workItemId, user);
		String reason = trimmedNote(note);
		if (reason == null) {
			throw ApiException.badRequest("error.time.approvalNoteRequired");
		}
		TimeLocks.LockState state =
				locks.lockStateFor(item.getUserId(), item.getProjectId(), item.getDate());
		if (state == null) {
			throw ApiException.badRequest("error.time.entryNotLocked");
		}
		Set<String> recipients = state.reason() == TimePolicy.LockReason.LOCK_DATE
				? adminIds()
				: approverIds(item.getProjectId());
		recipients.remove(user.getId());
		notifications.notifyTimeCorrectionRequested(recipients, user.getDisplayName(),
				"/time/approvals");
		audit.event(AuditAction.TIME_CORRECTION_REQUESTED).actor(user)
				.target(item.getId(), String.valueOf(item.getDate()))
				.meta("entry", item.getId())
				.meta("date", String.valueOf(item.getDate()))
				.meta("project", item.getProjectId())
				.meta("reason", state.reason().name())
				.meta("note", reason)
				.log();
	}

	// --- who may do what -------------------------------------------------------

	private static boolean isOwner(TimesheetApproval approval, User user) {
		return approval.getUserId() != null && approval.getUserId().equals(user.getId());
	}

	/**
	 * Whether this person may decide this submission.
	 *
	 * <p>Never their own — an approval is a check, and checking your own work is
	 * signing it. A lead who has submitted their own period waits for a second
	 * lead or an administrator, which is also why an administrator is not excluded
	 * from the rule: the instance has to have somebody who can decide a sole
	 * lead's period, and the administrator is that somebody.
	 *
	 * <p>A lead of the project, or an administrator. Deliberately <em>not</em>
	 * {@code canDeleteIssues}, which also admits Team-Admins: deciding whether
	 * somebody's recorded working time is accepted is a different authority from
	 * tidying up a backlog, and the wider rule would have handed it to everyone who
	 * administers any team that happens to own the project.
	 */
	private boolean canDecide(TimesheetApproval approval, User user) {
		if (isOwner(approval, user)) {
			return false;
		}
		if (user.isAdmin()) {
			return true;
		}
		return projects.findOptional(approval.getProjectId())
				.map(project -> projects.isLeadOrAdmin(project, user))
				.orElse(false);
	}

	private void assertMayDecide(TimesheetApproval approval, User user) {
		if (canDecide(approval, user)) {
			return;
		}
		// Its owner is told what the rule is; anybody else is not told the
		// submission exists.
		throw isOwner(approval, user)
				? ApiException.forbidden("error.time.approvalSelf")
				: ApiException.notFound("timesheetApproval");
	}

	private Collection<String> ledProjectIds(User user) {
		return projects.visibleTo(user).stream()
				.filter(project -> projects.isLeadOrAdmin(project, user))
				.map(Project::getId)
				.toList();
	}

	private Set<String> approverIds(String projectId) {
		Set<String> ids = new LinkedHashSet<>();
		projects.findOptional(projectId).ifPresent(project -> {
			if (project.getLeadIds() != null) {
				ids.addAll(project.getLeadIds());
			}
			if (project.getLeadId() != null) {
				ids.add(project.getLeadId());
			}
		});
		if (ids.isEmpty()) {
			// A project with no lead still has to have somebody to ask, or the
			// Art.-16 route would be a button that notifies nobody.
			ids.addAll(adminIds());
		}
		return ids;
	}

	/**
	 * Active administrators.
	 *
	 * <p>Through the repository's own query rather than by filtering every user:
	 * this is reached from the Art.-16 route, which anybody may call, and draining
	 * the user collection per request is how a courtesy feature becomes a way to
	 * make the server work.
	 */
	private Set<String> adminIds() {
		return users.findByRolesContainingAndActiveIsTrue(Role.ADMIN).stream()
				.map(User::getId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	// --- notifications and audit ------------------------------------------------

	/**
	 * Tells the approvers of each project that something is waiting.
	 *
	 * <p>One notice per project, not one per submission, and the submitter is
	 * never among the recipients even when they lead the project — a message about
	 * your own action is noise, and here it would also read as if the system
	 * expected you to approve it.
	 */
	private void notifyApprovers(List<TimesheetApproval> submitted, User actor) {
		Map<String, Set<String>> byProject = new LinkedHashMap<>();
		for (TimesheetApproval approval : submitted) {
			byProject.computeIfAbsent(approval.getProjectId(), this::approverIds);
		}
		byProject.forEach((projectId, recipients) -> {
			Set<String> ids = new LinkedHashSet<>(recipients);
			ids.remove(actor.getId());
			if (!ids.isEmpty()) {
				notifications.notifyTimesheetSubmitted(ids, actor.getDisplayName(),
						"/time/approvals");
			}
		});
	}

	private void notifyOwner(TimesheetApproval approval, NotificationService.TimesheetEvent event,
			User actor) {
		if (Objects.equals(approval.getUserId(), actor.getId())) {
			return;
		}
		users.findById(approval.getUserId()).ifPresent(owner -> notifications
				.notifyTimesheetDecided(owner, event, periodLabel(approval), "/time/timesheet"));
	}

	/**
	 * The span in ISO, for a mail that has to name a period and must not say
	 * "week". The reader's locale formats it; this is only the data.
	 */
	private static String periodLabel(TimesheetApproval approval) {
		return approval.getPeriodStart() + " – " + approval.getPeriodEnd();
	}

	private void audited(AuditAction action, TimesheetApproval approval, User actor, String note) {
		audit.event(action).actor(actor)
				.target(approval.getId(), periodLabel(approval))
				.meta("user", approval.getUserId())
				.meta("project", approval.getProjectId())
				.meta("periodStart", String.valueOf(approval.getPeriodStart()))
				.meta("periodEnd", String.valueOf(approval.getPeriodEnd()))
				.meta("periodType", String.valueOf(approval.getPeriodType()))
				.meta("note", note)
				.log();
	}

	// --- helpers ---------------------------------------------------------------

	private Map<String, Project> namesOf(Collection<String> projectIds, User user) {
		Map<String, Project> named = new LinkedHashMap<>();
		for (Project project : projects.resolveVisible(user, List.copyOf(projectIds))) {
			named.put(project.getId(), project);
		}
		return named;
	}

	private static String trimmedNote(String note) {
		if (note == null) {
			return null;
		}
		String trimmed = note.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > TimePolicy.LOCK_NOTE_MAX) {
			throw ApiException.badRequest("error.time.noteTooLong");
		}
		return trimmed;
	}

	/**
	 * Clamped rather than refused, the way every other paged route in the module
	 * does it — and the ceiling is not cosmetic: the driver takes a 32-bit skip, so
	 * a page index in the tens of millions overflows it into a negative number and
	 * the request comes back as a 500 with a stack trace on disk.
	 */
	private static Pageable pageOf(int page, int size) {
		return PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), TimesheetApprovalRepository.NEWEST_FIRST);
	}
}
