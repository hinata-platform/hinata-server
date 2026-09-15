package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * What the module deletes when an account is deleted, and what it keeps.
 *
 * <p>Deleted: the person's correction requests with their answers, and days opened
 * for them. Both are about somebody who is no longer there to follow them up.
 *
 * <p>Deleted: the submissions that were never signed off — handed in and waiting,
 * sent back, withdrawn. A pending submission is a request from somebody who is no
 * longer there to follow it up, and it would freeze the surviving entries against
 * an approver with nobody to answer to.
 *
 * <p>Kept: the <em>approved</em> ones (HIN-89). An approval is the business record
 * of a decision — "this span was accepted, by this person, on this day" — and the
 * payroll or invoice built on it does not stop having happened because the
 * employee left. It keeps freezing the entries it covers, which is what an
 * accepted period is for; an approver can still reopen it. The audit records of
 * every decision remain as well, because those are records of what
 * administrators and leads did.
 *
 * <p>Deleted: the running timer. It is live personal state about what somebody
 * is doing right now, it belongs to nobody once the account is gone, and it
 * would otherwise sit in {@code running_timers} forever holding a name for an id
 * that no longer resolves — swept hourly by a job that can never file it.
 *
 * <p>Kept: the entries. Hours worked on a project are the project's record, and
 * {@code work_items.userId} stays as a pseudonym exactly as a comment's author
 * id does ({@code UserService.delete} states the convention). Art. 17 DSGVO is
 * satisfied by the account being gone; the retention policy is what empties the
 * descriptions afterwards, on a schedule the operator sets — which is why the
 * pseudonym is written down here ({@link DepartedTimeUser}).
 *
 * <p>It listens rather than being called, so that {@code user} carries no
 * knowledge of this module — the same direction as {@code FeatureFlags.Module}
 * and {@code SettingsPrefill}. That inversion is what lets the whole package be
 * deleted without editing anything outside it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeTrackingErasure {

	private final RunningTimerRepository timers;
	private final TimesheetApprovalRepository approvals;
	private final DepartedTimeUserRepository departed;
	private final TimeCorrectionRequestRepository corrections;
	private final TimeBackfillGrantRepository grants;
	private final TimeMarks marks;
	private final Clock clock;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		// Separate steps, each allowed to fail on its own: a transient error writing
		// one collection must not leave the running timer or the open submissions
		// of a deleted person behind. The pseudonym is written last and tried more
		// than once: it is how the retention sweep learns that the person is gone,
		// and nothing later would tell it.
		step("running timer", userId, () -> timers.deleteByUserId(userId));
		step("unapproved submission(s)", userId, () -> approvals.deleteByUserIdAndStatusNot(userId,
				TimesheetApproval.Status.APPROVED));
		step("correction request(s)", userId, () -> corrections.deleteByUserId(userId));
		step("backfill grant(s)", userId, () -> grants.deleteByUserId(userId));
		step("reminder mark(s)", userId, () -> marks.forget(userId));
		step("pseudonym record", userId, () -> recordDeparture(userId));
	}

	/** How often the pseudonym record is tried before the failure is logged. */
	static final int PSEUDONYM_ATTEMPTS = 3;

	private long recordDeparture(String userId) {
		RuntimeException last = null;
		for (int attempt = 0; attempt < PSEUDONYM_ATTEMPTS; attempt++) {
			try {
				departed.save(new DepartedTimeUser(userId, clock.instant()));
				return 1L;
			}
			catch (RuntimeException ex) {
				last = ex;
			}
		}
		throw last;
	}

	private void step(String what, String userId, java.util.function.LongSupplier action) {
		try {
			long removed = action.getAsLong();
			if (removed > 0) {
				log.info("[time] {}: {} for deleted user {}", what, removed, userId);
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone. Failing here would turn a completed
			// erasure into a failed request and tell the user their deletion did
			// not happen, which is both alarming and untrue.
			log.warn("[time] could not remove {} of deleted user {}: {}", what, userId, ex.toString());
		}
	}
}
