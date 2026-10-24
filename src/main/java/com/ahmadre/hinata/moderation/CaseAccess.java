package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may see, decide, review and confirm a case (HIN-170). The one formulation of the conflict of
 * interest: every place that shows, counts, assigns, decides or notifies about a case asks here.
 *
 * <p>Nobody judges themselves. Whoever wrote the reported state is out on every purpose, and for
 * them the case does not exist: 404 on every route, before anything else is checked, guessed ids
 * included. Whoever reported it sees it, but does not decide it.
 */
@Component
@RequiredArgsConstructor
public class CaseAccess {

	/**
	 * What somebody wants to do with a case, and whom it leaves out besides whoever wrote the
	 * case's content, who is out of every purpose.
	 */
	public enum Purpose {
		/** List, counts, summary, opening it, notices about new and overdue cases. Nobody else. */
		SEE,
		/** Claiming, deciding, restoring, lifting a suspension, a legal hold. Whoever reported it. */
		DECIDE,
		/** Deciding an appeal. Whoever reported it, and whoever decided or confirmed the decision. */
		REVIEW_APPEAL,
		/**
		 * The second pair of eyes. Whoever reported it, and whoever asked for it: who wants to see
		 * the evidence, or who decided to suspend a person with a role.
		 */
		SECOND_PERSON
	}

	private final ModerationAccess access;
	private final UserRepository users;
	private final MongoTemplate mongo;

	/** A case as the signed-in moderator may use it for one purpose. */
	public record Guarded(User moderator, ModerationCase moderationCase) {
	}

	/**
	 * The case [caseId] for the signed-in person and [purpose]: 403 for whoever does not moderate,
	 * 404 for an unknown case and for whoever wrote its content, 409 for whoever reported it when
	 * the purpose decides anything.
	 */
	public Guarded require(String caseId, Purpose purpose) {
		User moderator = access.require();
		ModerationCase found = caseId == null ? null : mongo.findById(caseId, ModerationCase.class);
		if (found == null || contributed(found, moderator.getId())) {
			throw ApiException.notFound("moderationCase");
		}
		if (!eligible(moderator, found, purpose)) {
			throw ApiException.conflict("error.moderation.conflictOfInterest");
		}
		return new Guarded(moderator, found);
	}

	/** The cases of [filter] that [viewer] may see ({@link Purpose#SEE}): all but those they wrote content for. */
	public Criteria visibleTo(User viewer, Criteria filter) {
		return new Criteria().andOperator(filter, Criteria.where("contributorIds").ne(viewer.getId()));
	}

	/**
	 * How many cases of [filter] [viewer] may see: all of them less those they wrote content for.
	 * Two counts off indexes, where one count of everything not theirs would read every case.
	 */
	public long countVisible(User viewer, Criteria filter) {
		long all = mongo.count(Query.query(filter), ModerationCase.class);
		long theirs = mongo.count(Query.query(new Criteria().andOperator(
				Criteria.where("contributorIds").is(viewer.getId()), filter)), ModerationCase.class);
		return Math.max(all - theirs, 0);
	}

	/** Whether [person] may serve [purpose] on [moderationCase]. */
	public boolean eligible(User person, ModerationCase moderationCase, Purpose purpose) {
		return person.isActive() && person.isModerator()
				&& !excludedFrom(moderationCase, purpose).contains(person.getId());
	}

	/** The active moderators, read once for a run or a request that asks about several cases. */
	public List<User> moderators() {
		return access.moderators();
	}

	/** The active admins, read once for a run that asks about several cases. */
	public List<User> admins() {
		return users.findByRolesContainingAndActiveIsTrue(Role.ADMIN);
	}

	/** The active moderators who may serve [purpose] on [moderationCase]. */
	public List<User> eligibleModerators(ModerationCase moderationCase, Purpose purpose) {
		return eligibleModerators(moderationCase, purpose, moderators());
	}

	/** Those of [moderators] who may serve [purpose] on [moderationCase]. */
	public List<User> eligibleModerators(ModerationCase moderationCase, Purpose purpose, List<User> moderators) {
		Set<String> excluded = excludedFrom(moderationCase, purpose);
		return moderators.stream().filter(person -> !excluded.contains(person.getId())).toList();
	}

	/**
	 * Those of [admins] outside the case's content, for notices that carry none: a case waiting a
	 * day, nobody left to decide.
	 */
	public List<User> eligibleAdmins(ModerationCase moderationCase, List<User> admins) {
		Set<String> contributors = contributorsOf(moderationCase);
		return admins.stream().filter(admin -> !contributors.contains(admin.getId())).toList();
	}

	/** Whether [userId] wrote the case's content. */
	public boolean contributed(ModerationCase moderationCase, String userId) {
		return contributorsOf(moderationCase).contains(userId);
	}

	/** Whether [userId] reported [moderationCase]. */
	public boolean reported(ModerationCase moderationCase, String userId) {
		return mongo.exists(Query.query(Criteria.where("caseId").is(moderationCase.getId())
				.and("reporterKey").is(userId)), ContentReport.class);
	}

	private Set<String> excludedFrom(ModerationCase moderationCase, Purpose purpose) {
		Set<String> excluded = new HashSet<>(contributorsOf(moderationCase));
		if (purpose == Purpose.SEE) {
			return excluded;
		}
		excluded.addAll(reporterIdsOf(moderationCase));
		if (purpose == Purpose.REVIEW_APPEAL && moderationCase.getDecision() != null) {
			// Both who decided and who confirmed a suspension made the decision under review.
			if (moderationCase.getDecision().decidedBy() != null) {
				excluded.add(moderationCase.getDecision().decidedBy());
			}
			if (moderationCase.getDecision().confirmedBy() != null) {
				excluded.add(moderationCase.getDecision().confirmedBy());
			}
		}
		if (purpose == Purpose.SECOND_PERSON && moderationCase.getEvidenceReveal() != null
				&& moderationCase.getEvidenceReveal().requestedBy() != null) {
			excluded.add(moderationCase.getEvidenceReveal().requestedBy());
		}
		if (purpose == Purpose.SECOND_PERSON && moderationCase.getPendingDecision() != null) {
			// Who decided to suspend a person with a role does not confirm it (HIN-172).
			excluded.add(moderationCase.getPendingDecision().decidedBy());
		}
		return excluded;
	}

	private static Set<String> contributorsOf(ModerationCase moderationCase) {
		return moderationCase.getContributorIds() == null ? Set.of() : moderationCase.getContributorIds();
	}

	/** The members who reported the case; one indexed read over {@code case_reporter}. */
	private Set<String> reporterIdsOf(ModerationCase moderationCase) {
		Query query = Query.query(Criteria.where("caseId").is(moderationCase.getId())
				.and("reporterId").exists(true));
		query.fields().include("reporterId");
		Set<String> ids = new HashSet<>();
		mongo.find(query, ContentReport.class).forEach(report -> ids.add(report.getReporterId()));
		return ids;
	}
}
