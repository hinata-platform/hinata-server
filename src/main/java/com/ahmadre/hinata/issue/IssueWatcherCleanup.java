package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.notification.IssueDigestService;
import com.ahmadre.hinata.project.ProjectReach;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Prunes watcher subscriptions when the access that allowed them is taken away.
 *
 * <p>This is <em>hygiene, not the guarantee</em>. What actually keeps a former
 * member from hearing about an issue is the access re-check the notification
 * fan-out and the digest sweep run on every delivery — this only stops stale ids
 * from accumulating in watcher lists (and a queued mail from going out for
 * nothing). Written that way round on purpose: a cleanup that silently misses a
 * path is a leak, whereas a delivery check that is asked every time cannot be.
 *
 * <p>Because it is hygiene, it must never fail its caller: revoking a team grant
 * or deleting a project cannot be reported as failed — or worse, abandoned half
 * way — because a watcher list could not be tidied. That guard lives here, where
 * the contract is written, rather than being copy-pasted into every caller and
 * forgotten by one of them.
 *
 * <p>Deliberately tiny and repository-only, so the services that revoke access
 * ({@code ProjectService}, {@code TeamService}, {@code DeletionService}) can all
 * call it without any of them depending on the issue service — which depends on
 * them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueWatcherCleanup {

	private final MongoTemplate mongo;
	private final IssueDigestService digests;
	// Asked before pruning: the caller knows it revoked something, not whether the
	// user still reaches the project by another route.
	private final ProjectReach reach;
	// A participant reaches the issue without reaching its project (HIN-136); their
	// subscription stays as long as the share does.
	private final IssueReach issueReach;
	private final IssueActivityRepository activities;
	private final AuditService audit;

	/**
	 * Drops from every watcher list in one project those of {@code userIds} who can
	 * no longer see it, and throws away whatever was queued for them there. Mirrors
	 * the {@code $pull} that {@code UserService.delete} does when an account goes
	 * away.
	 *
	 * <p>The access question is asked here rather than by the caller: a service
	 * that just dropped someone from a member list knows what it revoked, not
	 * whether the user still reaches the project through a team grant or a second
	 * team. Unsubscribing one of those would be a silent
	 * loss the user never asked for and would never notice.
	 */
	public void removeFromProject(String projectId, Collection<String> userIds) {
		if (projectId == null || userIds == null || userIds.isEmpty()) return;
		bestEffort(projectId, () -> {
			Set<String> stillReaching = reach.whoCanSee(projectId, userIds);
			List<String> ids = userIds.stream()
					.filter(id -> id != null && !id.isBlank() && !stillReaching.contains(id))
					.toList();
			if (ids.isEmpty()) return;
			// One write for the whole project. Each issue keeps the subscriptions of the people
			// it is shared with (HIN-136): a team change can name somebody the team never gave
			// this project, whose only way in is a share. watcherIds minus (ids minus participants).
			List<Document> pipeline = List.of(new Document("$set", new Document("watcherIds",
					new Document("$setDifference", List.of("$watcherIds",
							new Document("$setDifference", List.of(ids,
									new Document("$ifNull", List.of("$participantIds", List.of())))))))));
			mongo.getCollection(mongo.getCollectionName(Issue.class)).updateMany(
					new Document("projectId", projectId).append("watcherIds", new Document("$in", ids)), pipeline);
			digests.discardFor(projectId, ids);
			log.debug("Removed {} watcher(s) from project {}", ids.size(), projectId);
		});
	}

	/**
	 * Ends the shares of those of {@code userIds} who now reach the project (HIN-136): somebody
	 * who joins sees every issue anyway, and a share left behind would wake up the day they
	 * leave again. Each ended share is written to the issue's history and the audit log, with
	 * no actor: nobody removed them, the membership made the share redundant.
	 *
	 * <p>Called from every path that grants a project (member list, team grants, moving an
	 * issue, migrating a project's issues); its failure is logged, never thrown at the caller.
	 */
	public void endSharesOfMembers(String projectId, Collection<String> userIds) {
		if (projectId == null || userIds == null || userIds.isEmpty()) return;
		bestEffort(projectId, () -> {
			Set<String> reaching = reach.whoCanSee(projectId, userIds);
			if (reaching.isEmpty()) return;
			Query shared = new Query(Criteria.where("projectId").is(projectId).and("participantIds").in(reaching));
			shared.fields().include("_id", "readableId", "participantIds");
			for (Document issue : mongo.find(shared, Document.class, mongo.getCollectionName(Issue.class))) {
				List<String> ended = new ArrayList<>(issue.getList("participantIds", String.class));
				ended.retainAll(reaching);
				endShares(String.valueOf(issue.get("_id")), issue.getString("readableId"), ended);
			}
		});
	}

	/** Takes {@code ended} off one issue, with a history row and an audit entry each. */
	private void endShares(String issueId, String readableId, List<String> ended) {
		if (ended.isEmpty()) return;
		mongo.updateFirst(new Query(Criteria.where("_id").is(issueId)), new Update()
				.pullAll("participantIds", ended.toArray())
				.pull("participantMeta", new Document("userId", new Document("$in", ended))), Issue.class);
		for (String userId : ended) {
			activities.save(IssueActivity.builder().issueId(issueId).field(IssueActivity.Field.PARTICIPANT_REMOVED)
					.fromValue(userId).build());
			audit.event(AuditAction.ISSUE_PARTICIPANT_REMOVED).target(userId, userId)
					.meta("issue", readableId).meta("reason", "joinedProject").log();
		}
		log.debug("Ended {} share(s) on issue {}: they joined its project", ended.size(), issueId);
	}

	/**
	 * Clears every watcher of every issue in a project that is going away
	 * (archived, or migrated elsewhere), together with the mails still queued for
	 * it.
	 *
	 * <p>The {@code watcherIds.0} predicate is not an optimisation detail: the vast
	 * majority of issues have no watchers at all, and without it a project with
	 * twenty thousand issues writes twenty thousand documents, oplog entries and
	 * index updates to set a field that is already empty.
	 *
	 * <p>For a project whose issues are about to be <em>deleted</em>, use
	 * {@link #discardQueuedMail(String)} instead — see there.
	 */
	public void clearProject(String projectId) {
		if (projectId == null) return;
		bestEffort(projectId, () -> {
			mongo.updateMulti(new Query(Criteria.where("projectId").is(projectId)
					.and("watcherIds.0").exists(true)),
					new Update().set("watcherIds", List.of()), Issue.class);
			digests.discardAllFor(projectId);
		});
	}

	/**
	 * Throws away every mail still queued for a project, without touching the
	 * issues.
	 *
	 * <p>For the delete strategy this is the whole of the work worth doing: the
	 * issues themselves are removed microseconds later, so clearing their watcher
	 * lists first is a write per issue whose only effect is to be discarded. The
	 * queued mails are the part that outlives the issues and would otherwise be
	 * sent minutes after the project stopped existing.
	 */
	public void discardQueuedMail(String projectId) {
		if (projectId == null) return;
		bestEffort(projectId, () -> digests.discardAllFor(projectId));
	}

	/**
	 * Prunes the watchers of one issue that has just moved to another project, and
	 * throws away what was queued for them.
	 *
	 * <p>A subscription is to an issue, but the access that allowed it was to a
	 * project — and the issue just left that project. Everyone who cannot see where
	 * it landed loses the subscription, and the mail already waiting for them is
	 * dropped rather than delivered with a title from a project they may not see.
	 * The delivery-time check refuses those bundles anyway; this is what stops them
	 * accumulating.
	 *
	 * <p>{@code issue} already belongs to {@code targetProjectId}; whoever it is shared with keeps
	 * it, and their subscription with it.
	 *
	 * <p>Mutates {@code issue}'s in-memory list as well as the document, so the
	 * change notice the move raises immediately afterwards fans out to the watchers
	 * that are left rather than to the ones it just removed.
	 */
	public void pruneAfterMove(Issue issue, String targetProjectId) {
		if (issue == null || targetProjectId == null) return;
		// Whoever the issue is shared with and who belongs to the target project needs the
		// share no longer.
		List<String> shared = issue.getParticipantIds();
		if (shared != null && !shared.isEmpty()) {
			bestEffort(targetProjectId, () -> {
				List<String> ended = new ArrayList<>(reach.whoCanSee(targetProjectId, shared));
				endShares(issue.getId(), issue.getReadableId(), ended);
				List<String> remaining = new ArrayList<>(shared);
				remaining.removeAll(ended);
				issue.setParticipantIds(remaining);
			});
		}
		List<String> watchers = issue.getWatcherIds();
		if (watchers == null || watchers.isEmpty()) return;
		bestEffort(targetProjectId, () -> {
			Set<String> stillReaching = issueReach.whoCanSee(issue, watchers);
			List<String> removed = watchers.stream()
					.filter(id -> id != null && !stillReaching.contains(id))
					.toList();
			if (removed.isEmpty()) return;
			mongo.updateFirst(new Query(Criteria.where("_id").is(issue.getId())),
					new Update().pullAll("watcherIds", removed.toArray()), Issue.class);
			List<String> remaining = new ArrayList<>(watchers);
			remaining.removeAll(removed);
			issue.setWatcherIds(remaining);
			removed.forEach(userId -> digests.discard(userId, issue.getId()));
			log.debug("Removed {} watcher(s) from moved issue {}", removed.size(), issue.getId());
		});
	}

	/**
	 * Runs one piece of hygiene without letting it reach the caller. See the class
	 * comment: every caller here is in the middle of something that matters more
	 * than tidy watcher lists — a membership change, a project deletion mid-cascade
	 * — and none of them can be failed or abandoned over this.
	 */
	private void bestEffort(String projectId, Runnable work) {
		try {
			work.run();
		}
		catch (RuntimeException ex) {
			log.warn("Pruning watchers of project {} failed", projectId, ex);
		}
	}
}
