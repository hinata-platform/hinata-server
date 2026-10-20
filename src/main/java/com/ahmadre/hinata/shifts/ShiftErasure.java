package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.project.ProjectDeleted;
import com.ahmadre.hinata.project.ProjectMemberRemoved;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the module clears when a membership, a project or an account ends (HIN-45). The project and
 * user domains announce it ({@link ProjectMemberRemoved}, {@link ProjectDeleted},
 * {@link UserService.UserDeletedEvent}) without knowing this module exists.
 *
 * <ul>
 * <li><b>Somebody lost a project</b>: their planner entry goes, so the right never comes back on its
 * own when they rejoin, and every place they hold on a shift that has not ended yet is freed, one
 * transaction per assignment, the counter given back only when the status changed. Places on
 * shifts that are over stay as they are: they are the record of who covered what, and the
 * retention run removes them in time.</li>
 * <li><b>A project is deleted</b>: every document of the module that belongs to it.</li>
 * <li><b>An account is deleted</b>: its places are freed first, so the counters stay right, then
 * every assignment, planner entry, guard and calendar feed token of the person is deleted.</li>
 * </ul>
 *
 * <p>Each listener is idempotent, runs whether or not the module is switched on today (the data is
 * there either way), and catches its own failure: the change that triggered it has already
 * happened and must not be reported as failed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftErasure {

	static final String REASON_MEMBERSHIP = "membershipEnded";
	static final String REASON_ACCOUNT = "accountDeleted";

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAssignments assignments;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	@EventListener
	public void onMemberRemoved(ProjectMemberRemoved event) {
		try {
			revokePlanner(event.projectId(), event.userId());
			Instant now = clock.instant();
			// Not over yet: ending after now, so starting after a day before now (user_start index).
			List<ShiftAssignment> held = mongo.find(Query.query(Criteria.where("userId").is(event.userId())
					.and("startsAt").gt(now.minus(ShiftRules.SHIFT_MAX))
					.and("endsAt").gt(now)
					.and("projectId").is(event.projectId())
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
			free(held, REASON_MEMBERSHIP);
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] could not clear project {} for removed member {}: {}", event.projectId(),
					event.userId(), failure.toString());
		}
	}

	@EventListener
	public void onProjectDeleted(ProjectDeleted event) {
		try {
			Query byProject = Query.query(Criteria.where("projectId").is(event.projectId()));
			// HIN-161 puts shift_series first here, so no series materialises into a deleted project.
			mongo.remove(byProject, ShiftAssignment.class);
			mongo.remove(byProject, Shift.class);
			mongo.remove(byProject, ShiftEvent.class);
			mongo.remove(byProject, ShiftPlanner.class);
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] could not clear deleted project {}: {}", event.projectId(), failure.toString());
		}
	}

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		try {
			Query byUser = Query.query(Criteria.where("userId").is(userId));
			free(mongo.find(Query.query(Criteria.where("userId").is(userId)
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class), REASON_ACCOUNT);
			mongo.remove(byUser, ShiftAssignment.class);
			mongo.remove(byUser, ShiftPlanner.class);
			mongo.remove(byUser, ShiftPersonalData.FEED_TOKENS);
			mongo.remove(Query.query(Criteria.where("_id").is(userId)), ShiftUserGuard.class);
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] could not finish shift erasure for deleted user {}: {}", userId, failure.toString());
		}
	}

	/**
	 * Frees each place in its own transaction. Without transactions the module has never run on this
	 * configuration's terms and keeps no counters anybody reads, so a plain status write is enough.
	 */
	private void free(List<ShiftAssignment> held, String reason) {
		if (held.isEmpty()) {
			return;
		}
		if (!tx.available()) {
			mongo.updateMulti(Query.query(Criteria.where("_id").in(held.stream().map(ShiftAssignment::getId).toList())
					.and("status").in(ShiftAssignment.Status.ACTIVE)),
					new Update().set("status", ShiftAssignment.Status.RELEASED), ShiftAssignment.class);
			return;
		}
		for (ShiftAssignment assignment : held) {
			assignments.releaseBySystem(assignment, reason);
		}
	}

	private void revokePlanner(String projectId, String userId) {
		Query entry = Query.query(Criteria.where("projectId").is(projectId).and("userId").is(userId));
		if (!tx.available()) {
			mongo.remove(entry, ShiftPlanner.class);
			return;
		}
		tx.run(() -> {
			if (mongo.remove(entry, ShiftPlanner.class).getDeletedCount() > 0) {
				events.publishEvent(new ShiftDomainEvents.Audited(AuditAction.SHIFT_PLANNER_REVOKED, null, null, userId,
						Map.of("projectId", projectId, "userId", userId, "reason", REASON_MEMBERSHIP)));
			}
		});
	}
}
