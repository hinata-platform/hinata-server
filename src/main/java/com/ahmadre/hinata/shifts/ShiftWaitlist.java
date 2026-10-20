package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The waiting list of a full shift (HIN-162): moving people up when a place opens, and taking the
 * place back from somebody who moved up and did not confirm in time.
 *
 * <p>The order is the waiting entries' {@code (createdAt, _id)}, read over the partial index
 * {@code waitlist_order}; nothing on the shift counts the list. Moving up runs after the commit of
 * whatever opened the place ({@link ShiftDomainEvents.SeatsOpened}), on the module's follow-up
 * thread ({@link ShiftFollowUps}) so the request that freed the place does not wait for it, one
 * transaction per candidate, each starting with the candidate's own {@link ShiftUserGuard}, so a sign-up of the same
 * person running at that moment is serialised against it:
 * <ol>
 * <li>the candidate still waits, is active and still sees the project; otherwise the entry is
 * released without a word;</li>
 * <li>the candidate holds no other place at that time; otherwise the entry is released and they
 * hear why ({@code SHIFT_WAITLIST_SKIPPED});</li>
 * <li>a place is claimed with the same conditional increment as every sign-up; none left ends the
 * round;</li>
 * <li>the entry becomes {@code ASSIGNED} with {@code promotedAt} and {@code confirmBy}, by a
 * compare-and-set on {@code WAITING}; a candidate who left the list meanwhile rolls the claim
 * back.</li>
 * </ol>
 * Nobody moves up when the event is closed for sign-up (the planners see the list and decide),
 * cancelled, or the shift starts within {@code hinata.shifts.waitlist-cutoff-minutes}. The hourly
 * {@link ShiftWaitlistJob} is the net under all of it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftWaitlist {

	/** Most people waiting for one shift. */
	public static final int WAITING_MAX = 50;

	/** The least time somebody who moved up gets to confirm. */
	static final Duration CONFIRM_AT_LEAST = Duration.ofMinutes(30);

	/** The most time somebody who moved up gets to confirm. */
	static final Duration CONFIRM_AT_MOST = Duration.ofHours(24);

	/** Confirmation is due at the latest this long before the shift starts. */
	static final Duration CONFIRM_BEFORE_START = Duration.ofHours(2);

	static final String REASON_SKIPPED = "waitlistSkipped";
	static final String REASON_INELIGIBLE = "waitlistIneligible";
	static final String REASON_EXPIRED = "confirmExpired";

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAccess access;
	private final ShiftSettings settings;
	private final UserRepository users;
	private final ApplicationEventPublisher events;
	private final ShiftSeats seats;
	private final HinataProperties properties;
	private final Clock clock;

	/** What one candidate came to. */
	enum Outcome {
		/** Moved up onto the place. */
		PROMOTED,
		/** Passed over and told why: they hold another shift at that time. */
		SKIPPED_OVERLAP,
		/** Passed over without a word: no longer active or no longer seeing the project. */
		SKIPPED_INELIGIBLE,
		/** Had left the list already, or left it while the place was being claimed. */
		LEFT,
		/** No place left, or the shift is gone: the round ends. */
		NO_PLACE
	}

	/**
	 * Places opened; move people up, on the follow-up thread. Handed over ahead of the other
	 * after-commit listeners, so a place filled from the list is no longer reported as short. Only
	 * the shifts somebody waits for are tried, found in one read.
	 */
	@Async(ShiftFollowUps.EXECUTOR)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSeatsOpened(ShiftDomainEvents.SeatsOpened event) {
		for (String shiftId : waitedFor(event.shiftIds())) {
			try {
				promote(shiftId);
			}
			catch (RuntimeException failure) {
				// The freed place is committed; the hourly job moves people up if this did not.
				log.warn("[shifts] moving up on shift {} failed: {}", shiftId, failure.toString(), failure);
			}
		}
	}

	/**
	 * Moves people up on [shiftId] while it has free places and people waiting. Returns how many
	 * moved up. The shift, its event and its project are read once per run; the list is read in
	 * order, a batch at a time, and every candidate's own transaction decides again whether there
	 * is a place, so a place that fills meanwhile ends the run.
	 */
	public int promote(String shiftId) {
		if (!settings.enabled()) {
			return 0;
		}
		Shift shift = mongo.findById(shiftId, Shift.class);
		if (shift == null || shift.isDeleted() || shift.isCancelled() || shift.full()) {
			return 0;
		}
		ShiftEvent event = mongo.findById(shift.getEventId(), ShiftEvent.class);
		if (event == null || event.isCancelled() || !event.isSelfAssignable() || !beforeCutoff(shift)) {
			return 0;
		}
		Optional<Project> project = access.project(shift.getProjectId());
		int promoted = 0;
		// Every candidate tried leaves the list or ends the run, so a batch read again holds only
		// people who joined meanwhile; twice the list's limit bounds the whole run.
		int tried = 0;
		while (tried < 2 * WAITING_MAX) {
			List<ShiftAssignment> batch = waiting(shiftId);
			if (batch.isEmpty()) {
				return promoted;
			}
			for (ShiftAssignment candidate : batch) {
				tried++;
				Outcome outcome = promoteOne(candidate, project);
				if (outcome == Outcome.PROMOTED) {
					promoted++;
				}
				else if (outcome == Outcome.NO_PLACE) {
					return promoted;
				}
			}
		}
		return promoted;
	}

	/**
	 * The person who moved up did not confirm by [due]: the place is freed, they hear it, and the
	 * next person moves up after the commit. A confirmation that commits first wins, because the
	 * release requires the place to still wait for one. Returns whether this call took it back.
	 */
	public boolean expire(String assignmentId, Instant due) {
		return Boolean.TRUE.equals(tx.run(() -> {
			ShiftAssignment expired = mongo.findAndModify(
					Query.query(Criteria.where("_id").is(assignmentId)
							.and("status").is(ShiftAssignment.Status.ASSIGNED)
							.and("confirmBy").lte(due)),
					new Update().set("status", ShiftAssignment.Status.RELEASED).unset("confirmBy"),
					FindAndModifyOptions.options().returnNew(true), ShiftAssignment.class);
			if (expired == null) {
				return false;
			}
			events.publishEvent(new ShiftDomainEvents.WaitlistExpired(expired.getShiftId(), expired.getEventId(),
					expired.getProjectId(), expired.getUserId()));
			// Like giving the place up: the next person moves up, and the planners hear it when the
			// shift is still short afterwards.
			seats.free(expired, expired.getUserId(), ShiftAssignment.Status.RELEASED);
			audited(AuditAction.SHIFT_ASSIGNMENT_RELEASED, expired, Map.of("reason", REASON_EXPIRED));
			return true;
		}));
	}

	/**
	 * Until when somebody who moves up now has to confirm: a day at most, two hours before the start
	 * at the latest, but never less than half an hour.
	 */
	static Instant confirmBy(Instant now, Instant startsAt) {
		Instant latest = now.plus(CONFIRM_AT_MOST);
		Instant beforeStart = startsAt.minus(CONFIRM_BEFORE_START);
		Instant due = latest.isBefore(beforeStart) ? latest : beforeStart;
		Instant least = now.plus(CONFIRM_AT_LEAST);
		return due.isBefore(least) ? least : due;
	}

	/** Whether the shift starts far enough ahead for anybody to move up. */
	boolean beforeCutoff(Shift shift) {
		return !shift.getStartsAt().isBefore(clock.instant().plus(cutoff()));
	}

	Duration cutoff() {
		return Duration.ofMinutes(Math.max(0, properties.getShifts().getWaitlistCutoffMinutes()));
	}

	/** The people waiting for [shiftId] in order, ids and user only, over the waitlist_order index. */
	private List<ShiftAssignment> waiting(String shiftId) {
		Query query = Query.query(Criteria.where("shiftId").is(shiftId)
				.and("status").is(ShiftAssignment.Status.WAITING))
				.with(Sort.by("createdAt", "_id")).limit(WAITING_MAX);
		query.fields().include("_id", "userId");
		return mongo.find(query, ShiftAssignment.class);
	}

	/** Which of [shiftIds] anybody waits for, in one read over the waitlist_order index. */
	private List<String> waitedFor(List<String> shiftIds) {
		if (shiftIds.isEmpty()) {
			return List.of();
		}
		return mongo.findDistinct(Query.query(Criteria.where("shiftId").in(shiftIds)
				.and("status").is(ShiftAssignment.Status.WAITING)), "shiftId", ShiftAssignment.class, String.class);
	}

	private Outcome promoteOne(ShiftAssignment candidate, Optional<Project> project) {
		String userId = candidate.getUserId();
		ShiftUserGuard.ensure(mongo, userId);
		try {
			return tx.runAlone(() -> {
				ShiftUserGuard.lock(mongo, userId);
				ShiftAssignment current = mongo.findById(candidate.getId(), ShiftAssignment.class);
				if (current == null || !current.waiting()) {
					return Outcome.LEFT;
				}
				// The checks a sign-up makes before its transaction, made here inside it: an active
				// account that still sees the project.
				User person = users.findById(userId).orElse(null);
				if (person == null || !person.isActive() || project.isEmpty() || !access.canSee(project.get(), person)) {
					return leave(current, REASON_INELIGIBLE) ? Outcome.SKIPPED_INELIGIBLE : Outcome.LEFT;
				}
				Shift shift = mongo.findById(current.getShiftId(), Shift.class);
				if (shift == null || shift.isDeleted() || shift.isCancelled()) {
					return Outcome.NO_PLACE;
				}
				List<ShiftAssignment> around = mongo.find(Query.query(
						ShiftAssignments.around(userId, shift.getStartsAt(), shift.getEndsAt())), ShiftAssignment.class);
				if (ShiftConflicts.firstOverlap(shift.getStartsAt(), shift.getEndsAt(), shift.getId(), around)
						.isPresent()) {
					if (!leave(current, REASON_SKIPPED)) {
						return Outcome.LEFT;
					}
					events.publishEvent(new ShiftDomainEvents.WaitlistSkipped(shift.getId(), shift.getEventId(),
							shift.getProjectId(), userId));
					return Outcome.SKIPPED_OVERLAP;
				}
				Instant now = clock.instant();
				Shift claimed = mongo.findAndModify(Query.query(ShiftAssignments.claimable(shift.getId())),
						new Update().inc("assignedCount", 1).set("updatedAt", now),
						FindAndModifyOptions.options().returnNew(true), Shift.class);
				if (claimed == null) {
					return Outcome.NO_PLACE;
				}
				Instant due = confirmBy(now, claimed.getStartsAt());
				ShiftAssignment promoted = mongo.findAndModify(
						Query.query(Criteria.where("_id").is(current.getId())
								.and("status").is(ShiftAssignment.Status.WAITING)),
						ShiftAssignment.copiesOf(claimed, now).set("status", ShiftAssignment.Status.ASSIGNED)
								.unset("assignedBy").set("promotedAt", now).set("confirmBy", due),
						FindAndModifyOptions.options().returnNew(true), ShiftAssignment.class);
				if (promoted == null) {
					// Left the list in between: give the claimed place back by rolling everything back.
					throw new LeftMeanwhile();
				}
				events.publishEvent(new ShiftDomainEvents.Promoted(promoted.getId(), promoted.getShiftId(),
						promoted.getEventId(), promoted.getProjectId(), userId, due));
				audited(AuditAction.SHIFT_ASSIGNMENT_CREATED, promoted, Map.of("self", "false", "promoted", "true"));
				return Outcome.PROMOTED;
			});
		}
		catch (LeftMeanwhile gone) {
			return Outcome.LEFT;
		}
	}

	/** The entry leaves the list, by a compare-and-set on {@code WAITING}; false when it already had. */
	private boolean leave(ShiftAssignment entry, String reason) {
		ShiftAssignment left = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(entry.getId()).and("status").is(ShiftAssignment.Status.WAITING)),
				new Update().set("status", ShiftAssignment.Status.RELEASED),
				FindAndModifyOptions.options().returnNew(true), ShiftAssignment.class);
		if (left == null) {
			return false;
		}
		audited(AuditAction.SHIFT_ASSIGNMENT_RELEASED, left, Map.of("waiting", "true", "reason", reason));
		return true;
	}

	/** An audit record without an actor: the system moved the list on. Ids only. */
	private void audited(AuditAction action, ShiftAssignment assignment, Map<String, String> extra) {
		Map<String, String> meta = assignment.auditMeta();
		meta.putAll(extra);
		events.publishEvent(new ShiftDomainEvents.Audited(action, null, null, assignment.getUserId(), meta));
	}

	/** The candidate left the list while their place was being claimed. */
	private static final class LeftMeanwhile extends RuntimeException {

		LeftMeanwhile() {
			super(null, null, false, false);
		}
	}
}
