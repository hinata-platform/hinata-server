package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.user.Suspension;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeping an account out for a while, and letting it back in (HIN-172). A suspension is a
 * deactivation with a reason and an end: it signs the person out everywhere and closes every
 * stream, like deactivating, and it always comes from a moderation case.
 *
 * <p>Every way a suspension ends comes here, and exactly once: whichever way gets here first
 * lifts it, the others find nothing and tell nobody. Lifting is announced as an event, so the
 * admin area never calls into moderation.
 */
@Service
@RequiredArgsConstructor
public class SuspensionService {

	/** How a suspension ended. */
	public enum LiftReason {
		/** Its time ran out. */
		EXPIRED,
		/** The appeal against its decision succeeded. */
		APPEAL,
		/** Moderation lifted it. */
		MODERATION,
		/** An administrator lifted it, or activated the account. */
		ADMIN,
		/** An administrator deactivated the account, which ends the suspension and keeps it out. */
		DEACTIVATED
	}

	/** A suspension ended: whose, from which case, how, and who ended it (null when its time ran out). */
	public record SuspensionLiftedEvent(String userId, String caseId, LiftReason reason, String actorId, String note) {
	}

	private final MongoTemplate mongo;
	private final UserService users;
	private final SessionService sessions;
	private final AuditService audit;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/**
	 * Refuses whom [by] may not suspend: an administrator (whom the admin area deactivates), oneself,
	 * and the last active person with the moderator or the organisation admin role.
	 */
	public void requireSuspendable(User person, User by) {
		if (person.isAdmin()) {
			throw ApiException.badRequest("error.moderation.cannotSuspendAdmin");
		}
		if (by != null && person.getId().equals(by.getId())) {
			throw ApiException.badRequest("error.moderation.cannotSuspendSelf");
		}
		users.requireAnotherActiveHolderOfEachRole(person);
	}

	/**
	 * Suspends [person] until [until], null for until further notice, as case [caseId] decided by
	 * [by]: inactive first, so a refresh racing the sign-out finds the account out, then every
	 * session and every stream ends.
	 */
	public User suspend(User person, Instant until, String caseId, User by) {
		requireSuspendable(person, by);
		Update update = new Update().set("active", false)
				.set("suspension", new Suspension(until, caseId, by.getId(), clock.instant()));
		User suspended = mongo.findAndModify(Query.query(Criteria.where("_id").is(person.getId())), update,
				FindAndModifyOptions.options().returnNew(true), User.class);
		if (suspended == null) {
			throw ApiException.notFound("user");
		}
		sessions.revokeAll(person.getId());
		audit.event(AuditAction.USER_SUSPENDED).actor(by).target(suspended)
				.meta("until", until == null ? "indefinite" : until.toString()).log();
		return suspended;
	}

	/**
	 * Ends the suspension of [person] the [reason] way: back in, or, when an administrator
	 * deactivates the account, kept out without an end. Answers false when there was none left to
	 * end, which tells the caller somebody else already did.
	 */
	public boolean lift(User person, LiftReason reason, User actor, String note) {
		Update update = new Update().unset("suspension");
		if (reason != LiftReason.DEACTIVATED) {
			update.set("active", true);
		}
		User before = mongo.findAndModify(Query.query(Criteria.where("_id").is(person.getId())
						.and("suspension").exists(true)), update, FindAndModifyOptions.options().returnNew(false),
				User.class);
		if (before == null) {
			return false;
		}
		audit.event(reason == LiftReason.EXPIRED ? AuditAction.USER_SUSPENSION_ENDED : AuditAction.USER_SUSPENSION_LIFTED)
				.actor(actor).target(before).log();
		events.publishEvent(new SuspensionLiftedEvent(before.getId(), before.getSuspension().caseId(), reason,
				actor == null ? null : actor.getId(), note));
		return true;
	}
}
