package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The planner role of a project (HIN-45): who may plan shifts there without leading it.
 *
 * <p>Every member reads the list, so the shift view can say who to ask. Only the people who run the
 * project, its leads and the admins of a team that owns it, give and take the role; a planner
 * cannot make another planner. The person has to see the project and hold an active account.
 * Granting twice keeps the one entry, and each change that actually happened is audited with actor
 * and target after the commit.
 */
@Service
@RequiredArgsConstructor
public class ShiftPlanners {

	/** Far above any real project; the list is not paged. */
	static final int LIST_MAX = 500;

	private final MongoTemplate mongo;
	private final ShiftTx tx;
	private final ShiftAccess access;
	private final ShiftReads reads;
	private final ShiftSettings settings;
	private final UserRepository users;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/** One planner. The person and who granted the role are named in {@code users}. */
	public record PlannerRow(String userId, String grantedBy, Instant createdAt) {

		static PlannerRow from(ShiftPlanner planner) {
			return new PlannerRow(planner.getUserId(), planner.getGrantedBy(), planner.getCreatedAt());
		}
	}

	/** A project's planners, oldest first, with everybody they mention named once. */
	public record PlannersResponse(List<PlannerRow> planners, Map<String, ShiftReads.UserCard> users) {
	}

	public PlannersResponse list(User caller, String projectId) {
		settings.requireEnabled();
		Project project = access.memberProject(projectId, caller);
		return of(project.getId());
	}

	/** Gives [userId] the role; 400 {@code plannerNotMember} unless they see the project and are active. */
	public PlannersResponse grant(User caller, String projectId, String userId) {
		settings.requireEnabled();
		Project project = access.managedProject(projectId, caller);
		User person = userId == null || userId.isBlank() ? null : users.findById(userId).orElse(null);
		if (person == null || !person.isActive() || !access.canSee(project, person)) {
			throw ShiftRules.refusal(HttpStatus.BAD_REQUEST, "error.shifts.plannerNotMember");
		}
		try {
			tx.run(() -> {
				UpdateResult result = mongo.upsert(entry(project.getId(), person.getId()),
						new Update().setOnInsert("grantedBy", caller.getId()).setOnInsert("createdAt", clock.instant()),
						ShiftPlanner.class);
				if (result.getUpsertedId() != null) {
					audited(AuditAction.SHIFT_PLANNER_GRANTED, caller, project.getId(), person.getId());
				}
			});
		}
		catch (DuplicateKeyException granted) {
			// A parallel grant inserted the entry first and audited it; the role is there either way.
			// Caught outside the transaction, which a failed write has already ended.
		}
		return of(project.getId());
	}

	/** Takes the role from [userId]; nothing to take is no error. */
	public void revoke(User caller, String projectId, String userId) {
		settings.requireEnabled();
		Project project = access.managedProject(projectId, caller);
		tx.run(() -> {
			if (mongo.remove(entry(project.getId(), userId), ShiftPlanner.class).getDeletedCount() > 0) {
				audited(AuditAction.SHIFT_PLANNER_REVOKED, caller, project.getId(), userId);
			}
		});
	}

	private PlannersResponse of(String projectId) {
		List<ShiftPlanner> entries = mongo.find(Query.query(Criteria.where("projectId").is(projectId))
				.with(Sort.by("createdAt", "_id")).limit(LIST_MAX), ShiftPlanner.class);
		Set<String> named = new LinkedHashSet<>();
		for (ShiftPlanner entry : entries) {
			named.add(entry.getUserId());
			if (entry.getGrantedBy() != null) {
				named.add(entry.getGrantedBy());
			}
		}
		return new PlannersResponse(entries.stream().map(PlannerRow::from).toList(), reads.users(named));
	}

	private static Query entry(String projectId, String userId) {
		return Query.query(Criteria.where("projectId").is(projectId).and("userId").is(userId));
	}

	/** A change of who may plan: the actor, the person as the target, ids only. */
	private void audited(AuditAction action, User actor, String projectId, String userId) {
		events.publishEvent(new ShiftDomainEvents.Audited(action, actor.getId(), ShiftDomainEvents.label(actor),
				userId, Map.of("projectId", projectId, "userId", userId)));
	}
}
