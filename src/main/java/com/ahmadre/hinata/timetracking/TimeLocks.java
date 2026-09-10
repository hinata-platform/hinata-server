package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.setup.ServerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Why a day is frozen, who can lift it, and what the way back is.
 *
 * <p>One component for every reason an entry can be immutable, because the
 * alternative is what this stage exists to prevent. After HIN-88 there are two
 * reasons and HIN-96 brings a third (an issued invoice), and three mechanisms
 * each raising their own refusal would give the person on the other end three
 * sentences, three vocabularies and three places to look for the way out. Here
 * they share one: {@link TimePolicy.LockReason}, {@link TimePolicy.LockHolder},
 * {@link TimePolicy.LockRemedy} — reason, who, how — and the client renders all
 * of them with one component.
 *
 * <p>Separate from {@link TimeTrackingService} rather than another pair of
 * methods on it, and not only for size. {@code TimesheetApprovalService} needs
 * the service (to read the entries of a period) <em>and</em> the service needs to
 * know about approvals (to refuse a write into one). Put both in one class and
 * that is a bean cycle; put the rule here and both depend on it instead.
 *
 * <p>Nothing here is reachable while {@code advancedEnabled} is off. The frozen
 * published app writes through the same service on the ungated 1.x routes, and a
 * deployment that set a lock date and never switched the module on must not start
 * refusing it.
 */
@Component
@RequiredArgsConstructor
public class TimeLocks {

	private final TimeTrackingSettings policy;
	private final ProjectTimeSettingsRepository projectSettings;
	private final TimesheetApprovalRepository approvals;

	/**
	 * Why one day cannot be written, in the shape the refusal travels in.
	 *
	 * <p>{@code holder} and {@code remedy} are derived from the reason and not
	 * stored beside it: who may lift a lock date is always an administrator and
	 * who may reopen an approval is always an approver, and letting a call site
	 * pass its own answer is how those drift apart.
	 */
	public record LockState(TimePolicy.LockReason reason, LocalDate lockDate,
			TimesheetApproval approval) {

		public TimePolicy.LockHolder holder() {
			return switch (reason) {
				case LOCK_DATE -> TimePolicy.LockHolder.ADMIN;
				case APPROVAL -> TimePolicy.LockHolder.APPROVER;
				case INVOICE -> TimePolicy.LockHolder.ACCOUNTING;
			};
		}

		public TimePolicy.LockRemedy remedy() {
			return switch (reason) {
				case LOCK_DATE -> TimePolicy.LockRemedy.LOCK_EXCEPTION;
				case APPROVAL -> TimePolicy.LockRemedy.REOPEN;
				case INVOICE -> TimePolicy.LockRemedy.CREDIT_NOTE;
			};
		}

		/**
		 * The facts of the refusal, for a client that has to offer the way back
		 * rather than print a sentence.
		 *
		 * <p>Lower camel words rather than the enum constants, because these are
		 * wire values a client switches on and {@code LOCK_DATE} is a Java
		 * spelling. Nothing personal is in here: an approval id and a date.
		 */
		public Map<String, String> details() {
			Map<String, String> details = new LinkedHashMap<>();
			details.put("reason", wire(reason.name()));
			details.put("holder", wire(holder().name()));
			details.put("remedy", wire(remedy().name()));
			if (lockDate != null) {
				details.put("lockDate", lockDate.toString());
			}
			if (approval != null) {
				details.put("approvalId", approval.getId());
				details.put("periodStart", String.valueOf(approval.getPeriodStart()));
				details.put("periodEnd", String.valueOf(approval.getPeriodEnd()));
			}
			return details;
		}

		/** The message that names this reason. Both spell the dates, never an instant. */
		ApiException refusal() {
			if (reason == TimePolicy.LockReason.APPROVAL && approval != null) {
				return ApiException.forbidden("error.time.approvalLocked", details(),
						day(approval.getPeriodStart()), day(approval.getPeriodEnd()));
			}
			// A java.util.Date rather than the LocalDate: MessageFormat formats a
			// Date for the reader's locale and calls toString() on anything else,
			// so the German sentence would otherwise carry an ISO string. The
			// messages spell {0,date,medium}, because a bare {0} renders date *and*
			// time and midnight is not part of this rule.
			return ApiException.forbidden("error.time.locked", details(), day(lockDate));
		}

		private static Date day(LocalDate date) {
			return date == null ? null : Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
		}

		private static String wire(String name) {
			StringBuilder out = new StringBuilder(name.length());
			boolean upper = false;
			for (char c : name.toCharArray()) {
				if (c == '_') {
					upper = true;
					continue;
				}
				out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
				upper = false;
			}
			return out.toString();
		}
	}

	// --- the lock date ---------------------------------------------------------

	/**
	 * The freeze in force for a project, or null.
	 *
	 * <p>The <em>later</em> of the instance date and the project's own, never the
	 * project's outright. A project override closes a customer's books early while
	 * the rest of the instance runs on, which is what it is for; letting it also
	 * name an <em>earlier</em> date would hand a project lead the power to reopen
	 * the month an administrator archived, and lead is not administrator. Opening
	 * a closed span is {@link #exceptions()}, and that is admin-only.
	 */
	public LocalDate lockBefore(String projectId) {
		if (!policy.advancedEnabled()) {
			return null;
		}
		LocalDate instance = policy.lockBefore();
		LocalDate project = projectId == null ? null : projectSettings.findByProjectId(projectId)
				.map(ProjectTimeSettings::getLockBefore)
				.orElse(null);
		if (instance == null) {
			return project;
		}
		if (project == null) {
			return instance;
		}
		return project.isAfter(instance) ? project : instance;
	}

	/** The spans an administrator has reopened inside the freeze. Never null. */
	public List<ServerSettings.TimeTracking.LockException> exceptions() {
		if (!policy.advancedEnabled()) {
			return List.of();
		}
		return policy.lockExceptions();
	}

	/** Whether an exception reopens {@code date}. Both bounds included. */
	public boolean reopenedByException(LocalDate date) {
		if (date == null) {
			return false;
		}
		for (ServerSettings.TimeTracking.LockException exception : exceptions()) {
			LocalDate from = exception.getFrom();
			LocalDate to = exception.getTo();
			if (from != null && to != null && !date.isBefore(from) && !date.isAfter(to)) {
				return true;
			}
		}
		return false;
	}

	// --- the whole question ----------------------------------------------------

	/**
	 * Why this person cannot write this day of this project, or null when they can.
	 *
	 * <p>The lock date is asked first and the approval second, because the lock
	 * date is the cheaper question (a volatile read and at most one indexed point
	 * read) and because it is the more absolute answer: a day an administrator has
	 * archived stays archived whatever an approval says about it.
	 */
	public LockState lockStateFor(String userId, String projectId, LocalDate date) {
		if (date == null || !policy.advancedEnabled()) {
			return null;
		}
		LocalDate lock = lockBefore(projectId);
		if (lock != null && date.isBefore(lock) && !reopenedByException(date)) {
			return new LockState(TimePolicy.LockReason.LOCK_DATE, lock, null);
		}
		TimesheetApproval approval = freezingApproval(userId, projectId, date);
		return approval == null ? null
				: new LockState(TimePolicy.LockReason.APPROVAL, null, approval);
	}

	/**
	 * The submission that freezes this day, or null.
	 *
	 * <p>Containment, never a grid: "is there a SUBMITTED or APPROVED row for this
	 * person and project whose span holds this date". That is what keeps working
	 * after an operator changes the rhythm — a stored period keeps the days it
	 * named, and a question about today's grid would stop matching it.
	 *
	 * <p>Entries with no project are never covered. They are private, they belong
	 * to no lead, and there is nobody to approve them; the lock date covers them
	 * like everything else.
	 */
	public TimesheetApproval freezingApproval(String userId, String projectId, LocalDate date) {
		if (userId == null || projectId == null || date == null || !policy.approvalsEnabled()
				|| !policy.advancedEnabled()) {
			return null;
		}
		return approvals
				.findByUserIdAndProjectIdAndPeriodEndGreaterThanEqual(userId, projectId, date)
				.stream()
				.filter(TimesheetApproval::freezes)
				.filter(approval -> approval.covers(date))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Refuses a write that touches a frozen day, on either side of the change.
	 *
	 * <p>Both sides, which is the whole reason this is handed two entries: an edit
	 * that moves an entry <em>off</em> a frozen day changes that day's total as
	 * surely as one that moves it on, and a lock that only guarded the destination
	 * would be a lock anyone could walk out of. Moving an entry <em>into</em> an
	 * approved period is refused for the same reason, and so is giving a
	 * project-less entry a project whose period is already signed off — the tuple
	 * that decides is (owner, project, day), and both tuples are checked.
	 *
	 * <p>It binds administrators too. A freeze the most powerful account on the
	 * instance can edit around is not a freeze but a suggestion, and the payroll
	 * period it protects is exactly what an administrator is most likely to be
	 * asked to change. The way through is an audited act — a lock exception or a
	 * reopen — rather than an untraceable edit.
	 */
	public void assertWritable(WorkItem before, WorkItem after) {
		if (!policy.advancedEnabled()) {
			return;
		}
		// Deduplicated because `update` asks the gate twice and its first call
		// passes the same entry on both sides: without this, one edit would run
		// the same two indexed reads four times.
		Set<List<Object>> seen = new LinkedHashSet<>();
		for (WorkItem item : sides(before, after)) {
			List<Object> tuple = List.of(String.valueOf(item.getUserId()),
					String.valueOf(item.getProjectId()), String.valueOf(item.getDate()));
			if (!seen.add(tuple)) {
				continue;
			}
			LockState state = lockStateFor(item.getUserId(), item.getProjectId(), item.getDate());
			if (state != null) {
				throw state.refusal();
			}
		}
	}

	private static List<WorkItem> sides(WorkItem before, WorkItem after) {
		List<WorkItem> sides = new ArrayList<>(2);
		if (before != null && before.getDate() != null) {
			sides.add(before);
		}
		if (after != null && after.getDate() != null) {
			sides.add(after);
		}
		return sides;
	}
}
