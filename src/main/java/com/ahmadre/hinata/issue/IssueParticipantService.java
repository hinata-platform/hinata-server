package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.IssueDigestService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sharing one issue with people outside its project (HIN-136): who it is shared with, adding
 * somebody, and taking them off again.
 *
 * <p>Kept out of {@link IssueService} like {@link IssueWatchService}: its own small concern with
 * its own access story. Only people who reach the project share; a participant may only leave.
 * Every participant also watches the issue, so they hear about it through the paths watchers
 * already take, and leaving drops that subscription too.
 *
 * <p>The lists are written with {@code $addToSet}/{@code $pull} and the cap is part of the
 * update's filter, so two people sharing at once neither lose an entry nor push past it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueParticipantService {

	/** Most people one issue is shared with. */
	public static final int MAX_PARTICIPANTS = 25;

	/** Most ids one {@link #blocked} question may name: a comment's worth of mentions. */
	public static final int MAX_REACH_CHECK = 50;

	private final IssueService issues;
	private final IssueReach issueReach;
	private final IssueActivityRepository activities;
	private final ProjectReach projectReach;
	private final UserRepository users;
	private final NotificationService notifications;
	private final IssueDigestService digests;
	private final AuditService audit;
	private final MongoTemplate mongo;

	/** A participant as the share dialog shows them. */
	public record ParticipantView(String id, String displayName, String avatarUrl, String pronouns,
			Instant addedAt, String addedById, String addedByName) {
	}

	/**
	 * The people the issue is shared with who do not reach its project anyway: somebody who has
	 * joined the project since is a member now and no longer listed. Members only; at most
	 * {@link #MAX_PARTICIPANTS}, so not paged.
	 */
	public List<ParticipantView> list(String idOrReadableId, User caller) {
		Issue issue = editable(idOrReadableId, caller);
		List<String> ids = issue.getParticipantIds() == null ? List.of() : issue.getParticipantIds();
		if (ids.isEmpty()) {
			return List.of();
		}
		Set<String> members = projectReach.whoCanSee(issue.getProjectId(), ids);
		Map<String, Issue.ParticipantMeta> meta = new HashMap<>();
		if (issue.getParticipantMeta() != null) {
			issue.getParticipantMeta().forEach(entry -> meta.put(entry.userId(), entry));
		}
		// The participants and whoever added them, in one read.
		Set<String> wanted = new java.util.HashSet<>(ids);
		meta.values().forEach(entry -> {
			if (entry.addedById() != null) wanted.add(entry.addedById());
		});
		Map<String, User> byId = new HashMap<>();
		users.findAllById(wanted).forEach(user -> byId.put(user.getId(), user));
		return ids.stream()
				.filter(id -> !members.contains(id) && byId.containsKey(id))
				.map(id -> {
					User user = byId.get(id);
					Issue.ParticipantMeta entry = meta.get(id);
					User addedBy = entry == null || entry.addedById() == null ? null : byId.get(entry.addedById());
					return new ParticipantView(id, user.getDisplayName(), user.getAvatarUrl(), user.getPronouns(),
							entry == null ? null : entry.addedAt(), entry == null ? null : entry.addedById(),
							addedBy == null ? null : addedBy.getDisplayName());
				})
				.toList();
	}

	/**
	 * Shares the issue with {@code userId}. Idempotent: sharing again changes nothing, and only the
	 * write that actually added them is recorded, announced and audited.
	 */
	public void add(String idOrReadableId, String userId, User caller) {
		Issue issue = editable(idOrReadableId, caller);
		User target = userId == null ? null : users.findById(userId).orElse(null);
		if (target == null) {
			throw ApiException.badRequest("error.auth.unknownUser");
		}
		if (!target.isActive()) {
			throw ApiException.badRequest("error.issue.participantInactive");
		}
		// Already there, or a member of the project anyway: sharing would change nothing.
		if ((issue.getParticipantIds() != null && issue.getParticipantIds().contains(userId))
				|| projectReach.canSee(issue.getProjectId(), target)) {
			return;
		}
		Instant now = Instant.now();
		// Not yet on the list and room left, both in the filter: a concurrent share can neither
		// duplicate the entry nor take the list past the cap.
		Query room = Query.query(Criteria.where("_id").is(issue.getId())
				.and("participantIds").ne(userId)
				.and("participantIds." + (MAX_PARTICIPANTS - 1)).exists(false));
		UpdateResult result = mongo.updateFirst(room, new Update()
				.addToSet("participantIds", userId)
				.addToSet("watcherIds", userId)
				.push("participantMeta", new Issue.ParticipantMeta(userId, now, caller.getId())), Issue.class);
		if (result.getModifiedCount() == 0) {
			Issue current = issues.get(issue.getId());
			if (current.getParticipantIds() != null && current.getParticipantIds().contains(userId)) {
				return; // somebody else shared it a moment ago
			}
			throw ApiException.badRequest("error.issue.participantsFull", MAX_PARTICIPANTS);
		}
		activities.save(IssueActivity.builder().issueId(issue.getId()).actorId(caller.getId())
				.field(IssueActivity.Field.PARTICIPANT_ADDED).toValue(userId).build());
		audit.event(AuditAction.ISSUE_PARTICIPANT_ADDED).actor(caller).target(target)
				.meta("issue", issue.getReadableId()).log();
		try {
			notifications.notifyShared(issue, caller, userId);
		}
		catch (RuntimeException ex) {
			log.warn("share notification failed for issue {} (share kept)", issue.getId(), ex);
		}
	}

	/**
	 * Takes {@code userId} off the issue, with their subscription and any mail still queued for
	 * them. Members may remove anybody; anybody may remove themselves. Leaving goes through the
	 * plain lookup, not the access gate, so it never fails on the access it is giving up. Nothing
	 * else about them changes: an assignment stays an assignment.
	 */
	public void remove(String idOrReadableId, String userId, User caller) {
		boolean leaving = caller.getId().equals(userId);
		Issue issue = leaving ? issues.get(idOrReadableId) : editable(idOrReadableId, caller);
		if (leaving && !issues.canAccess(issue, caller)
				&& (issue.getParticipantIds() == null || !issue.getParticipantIds().contains(userId))) {
			// Nothing to leave, and nothing to learn about an issue one never had.
			throw ApiException.notFound("issue");
		}
		UpdateResult result = mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())
				.and("participantIds").is(userId)), new Update()
				.pull("participantIds", userId)
				.pull("watcherIds", userId)
				.pull("participantMeta", new org.bson.Document("userId", userId)), Issue.class);
		if (result.getModifiedCount() == 0) {
			return;
		}
		digests.discard(userId, issue.getId());
		activities.save(IssueActivity.builder().issueId(issue.getId()).actorId(caller.getId())
				.field(IssueActivity.Field.PARTICIPANT_REMOVED).fromValue(userId).build());
		User removed = users.findById(userId).orElse(null);
		var entry = audit.event(AuditAction.ISSUE_PARTICIPANT_REMOVED).actor(caller)
				.meta("issue", issue.getReadableId());
		(removed != null ? entry.target(removed) : entry.target(userId, userId)).log();
	}

	/**
	 * Which of {@code userIds} cannot open the issue, for the hint under a comment that mentions
	 * them. Members only: who reaches a project is the project's business. Unknown ids are left
	 * out rather than reported, so the answer names only real people who would miss the mention.
	 */
	public List<String> blocked(String idOrReadableId, Collection<String> userIds, User caller) {
		Issue issue = editable(idOrReadableId, caller);
		Set<String> asked = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
		asked.remove(null);
		if (asked.size() > MAX_REACH_CHECK) {
			throw ApiException.badRequest("error.issue.reachCheckTooMany", MAX_REACH_CHECK);
		}
		if (asked.isEmpty()) {
			return List.of();
		}
		Set<String> existing = new LinkedHashSet<>();
		users.findAllById(asked).forEach(user -> {
			if (user.isActive()) existing.add(user.getId());
		});
		Set<String> canSee = issueReach.whoCanSee(issue, existing);
		return asked.stream().filter(id -> existing.contains(id) && !canSee.contains(id)).toList();
	}

	/**
	 * The issue, if the caller may change it: 404 when they cannot see it at all, 403
	 * {@code participantReadOnly} when it is only shared with them.
	 */
	private Issue editable(String idOrReadableId, User caller) {
		Issue issue = issues.get(idOrReadableId);
		if (!issueReach.canSee(issue, caller)) {
			throw ApiException.notFound("issue");
		}
		// Seeing it and not only through a share means reaching the project.
		if (issueReach.isParticipantOnly(issue, caller)) {
			throw ApiException.forbidden("error.issue.participantReadOnly");
		}
		return issue;
	}
}
