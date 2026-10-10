package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared entries (HIN-95): somebody offers colleagues a copy of one of their entries, and each
 * colleague takes it into their own record or says no.
 *
 * <p>Three rules carry the feature, all from R2 of the epic. Before answering, the recipient
 * sees the invitation and never the sender's entry. Accepting files a copy that belongs to the
 * recipient alone, checked against their reach, locks, approvals and required fields like any
 * entry they type. And a refusal is silent: the sender learns that it was declined, never why,
 * and is not notified.
 *
 * <p>Only to people who can see the entry's project, because the invitation names it; an entry
 * without a project cannot be shared. The client resolves {@code +name} in a description into
 * user ids before it asks: the server reads no mentions out of free text.
 */
@Service
@RequiredArgsConstructor
public class TimeEntryShareService {

	/** Most people one entry is offered to, counting every invitation not taken back. */
	static final int RECIPIENTS_MAX = 20;

	/** Largest page of invitations. */
	static final int PAGE_MAX = 100;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"));

	private final TimeTrackingService timeTracking;
	private final ProjectReach projectReach;
	private final ProjectRepository projects;
	private final IssueRepository issues;
	private final UserRepository users;
	private final NotificationService notifications;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/** Which side of the invitations a reader asks for. */
	public enum Box {
		/** Invitations waiting for the reader's answer. */
		INBOX,
		/** Everything the reader offered, in every state. */
		SENT
	}

	/** A person as an invitation names them; the name is read now, never stored. */
	public record Person(String id, String name) {
	}

	/**
	 * One invitation as its reader sees it.
	 *
	 * <p>{@code entryId} only for the sender: the recipient never learns which of the sender's
	 * entries it was. {@code copyId} only for the recipient. The project and the issue are named
	 * only while the reader can see the project; otherwise the ids stay and the names are empty.
	 */
	public record ShareView(String id, TimeEntryShare.Status status, Instant createdAt, Instant decidedAt,
			Person from, Person to, String entryId, String copyId, String projectId, String projectName,
			String projectKey, String issueId, String issueKey, String issueTitle, LocalDate date,
			Integer durationMinutes, Instant startedAt, Instant endedAt, String activityType, String description,
			Boolean billable, List<String> tags) {
	}

	/**
	 * What the recipient may change before the copy is filed: where it sits and its tags. Null
	 * fields keep what the invitation says. A project without an issue files the copy on the
	 * project alone.
	 */
	public record Acceptance(String projectId, String issueId, List<String> tags) {
	}

	/** Somebody the caller may offer an entry to, in the directory's shape. */
	public record Candidate(String id, String username, String displayName, String avatarUrl) {
	}

	// --- the sender ----------------------------------------------------------------------------

	/**
	 * One page of the people the caller may share an entry of {@code projectId} with, by name:
	 * everybody who can see the project, active, and not the caller. {@code query} narrows by name
	 * or username, as a literal; the directory's other fields are not read.
	 */
	public Page<Candidate> candidates(String projectId, String query, int page, int size, User caller) {
		if (projectId == null || !projectReach.canSee(projectId, caller)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		Set<String> ids = new LinkedHashSet<>(projectReach.everyoneWhoCanSee(projectId));
		ids.remove(caller.getId());
		PageRequest request = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), Sort.by("displayName", "_id"));
		if (ids.isEmpty()) {
			return Page.empty(request);
		}
		Criteria criteria = Criteria.where("_id").in(ids.stream().filter(ObjectId::isValid).map(ObjectId::new)
				.toList()).and("active").is(true);
		String term = query == null ? "" : query.strip();
		if (!term.isEmpty()) {
			String literal = java.util.regex.Pattern.quote(term);
			criteria.orOperator(Criteria.where("displayName").regex(literal, "i"),
					Criteria.where("username").regex(literal, "i"));
		}
		Query rows = Query.query(criteria).with(request);
		rows.fields().include("_id", "username", "displayName", "avatarUrl");
		// Raw rows: a projected User fails on its primitive fields.
		List<Candidate> found = mongo.find(rows, org.bson.Document.class, mongo.getCollectionName(User.class))
				.stream().map(row -> new Candidate(row.getObjectId("_id").toHexString(), row.getString("username"),
						row.getString("displayName"), row.getString("avatarUrl")))
				.toList();
		return PageableExecutionUtils.getPage(found, request, () -> mongo.count(Query.query(criteria), User.class));
	}


	/**
	 * Offers the caller's own entry to {@code userIds}. Someone already asked stays as they are,
	 * whatever they answered; someone whose invitation was taken back is asked again.
	 *
	 * @return every invitation of the entry, as the sender sees them
	 */
	public List<ShareView> share(String entryId, Collection<String> userIds, User sender) {
		WorkItem entry = timeTracking.requireOwn(entryId, sender);
		if (entry.getProjectId() == null) {
			throw ApiException.badRequest("error.time.share.noProject");
		}
		Set<String> wanted = userIds == null ? Set.of() : userIds.stream().filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (wanted.isEmpty()) {
			throw ApiException.badRequest("error.time.share.noRecipients");
		}
		if (wanted.contains(sender.getId())) {
			throw ApiException.badRequest("error.time.share.self");
		}
		if (wanted.size() > RECIPIENTS_MAX) {
			throw ApiException.badRequest("error.time.share.tooMany", RECIPIENTS_MAX);
		}
		if (!projectReach.canSee(entry.getProjectId(), sender)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		// One answer for a person who is not in the project, is deactivated, or does not exist:
		// the route is no way to find out which accounts there are.
		Map<String, User> active = users.findAllById(wanted).stream().filter(User::isActive)
				.collect(Collectors.toMap(User::getId, Function.identity()));
		Set<String> reachable = projectReach.whoCanSee(entry.getProjectId(), active.keySet());
		if (!reachable.containsAll(wanted)) {
			throw ApiException.forbidden("error.time.share.notMember");
		}

		Map<String, TimeEntryShare> existing = sharesOf(entry.getId()).stream()
				.collect(Collectors.toMap(TimeEntryShare::getToUserId, Function.identity()));
		long standing = existing.values().stream()
				.filter(share -> share.getStatus() != TimeEntryShare.Status.REVOKED).count();
		long added = wanted.stream().filter(id -> !existing.containsKey(id)
				|| existing.get(id).getStatus() == TimeEntryShare.Status.REVOKED).count();
		if (standing + added > RECIPIENTS_MAX) {
			throw ApiException.badRequest("error.time.share.tooMany", RECIPIENTS_MAX);
		}

		Instant now = clock.instant();
		TimeEntryShare.Snapshot snapshot = TimeEntryShare.Snapshot.of(entry);
		Set<String> invited = new LinkedHashSet<>();
		for (String userId : wanted) {
			TimeEntryShare before = existing.get(userId);
			if (before == null) {
				try {
					mongo.insert(TimeEntryShare.builder().entryId(entry.getId()).fromUserId(sender.getId())
							.toUserId(userId).projectId(entry.getProjectId()).createdAt(now).entry(snapshot).build());
					invited.add(userId);
				}
				catch (DuplicateKeyException raced) {
					// The same person asked twice at once: one invitation is what was wanted.
				}
			}
			else if (before.getStatus() == TimeEntryShare.Status.REVOKED && mongo.updateFirst(
					Query.query(Criteria.where("_id").is(before.getId())
							.and("status").is(TimeEntryShare.Status.REVOKED)),
					new Update().set("status", TimeEntryShare.Status.PENDING).set("createdAt", now)
							.set("entry", snapshot).set("projectId", entry.getProjectId()).unset("decidedAt"),
					TimeEntryShare.class).getModifiedCount() > 0) {
				invited.add(userId);
			}
		}
		if (!invited.isEmpty()) {
			notifications.notifyTimeEntryShared(invited, sender.getDisplayName());
			for (String userId : invited) {
				audit.event(AuditAction.TIME_ENTRY_SHARED).actor(sender).target(active.get(userId))
						.meta("workItem", entry.getId()).meta("project", entry.getProjectId()).log();
			}
		}
		return views(sharesOf(entry.getId()), sender);
	}

	/** Every invitation of the caller's own entry; at most {@link #RECIPIENTS_MAX} are standing. */
	public List<ShareView> sharesOfEntry(String entryId, User sender) {
		WorkItem entry = timeTracking.requireOwn(entryId, sender);
		return views(sharesOf(entry.getId()), sender);
	}

	/** Takes an invitation back while it is unanswered. */
	public void revoke(String entryId, String toUserId, User sender) {
		TimeEntryShare share = mongo.findOne(Query.query(Criteria.where("entryId").is(entryId)
				.and("toUserId").is(toUserId).and("fromUserId").is(sender.getId())), TimeEntryShare.class);
		if (share == null) {
			throw ApiException.notFound("timeShare");
		}
		if (share.getStatus() == TimeEntryShare.Status.REVOKED) {
			return;
		}
		boolean revoked = mongo.updateFirst(Query.query(Criteria.where("_id").is(share.getId())
						.and("status").is(TimeEntryShare.Status.PENDING)),
				new Update().set("status", TimeEntryShare.Status.REVOKED).set("decidedAt", clock.instant()),
				TimeEntryShare.class).getModifiedCount() > 0;
		if (!revoked) {
			throw ApiException.conflict("error.time.share.answered");
		}
		audit.event(AuditAction.TIME_SHARE_REVOKED).actor(sender).target(users.findById(toUserId).orElse(null))
				.meta("workItem", entryId).meta("project", share.getProjectId()).log();
	}

	// --- both sides ----------------------------------------------------------------------------

	/** One page of the reader's open invitations, or of what they offered; newest first. */
	public Page<ShareView> page(Box box, int page, int size, User reader) {
		Criteria criteria = box == Box.SENT
				? Criteria.where("fromUserId").is(reader.getId())
				: Criteria.where("toUserId").is(reader.getId()).and("status").is(TimeEntryShare.Status.PENDING);
		PageRequest request = PageRequest.of(Math.clamp(page, 0, TimeTrackingService.PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), NEWEST_FIRST);
		List<TimeEntryShare> rows = mongo.find(Query.query(criteria).with(request), TimeEntryShare.class);
		return PageableExecutionUtils.getPage(views(rows, reader), request,
				() -> mongo.count(Query.query(criteria), TimeEntryShare.class));
	}

	// --- the recipient -------------------------------------------------------------------------

	/**
	 * Files the copy and answers with it. A second call answers with the copy the first made.
	 *
	 * <p>The answer is claimed before the copy is written, so an invitation taken back in the
	 * same moment cannot also be accepted; when the copy is refused — a frozen day, a required
	 * field, a project the recipient left — the claim is given back and the invitation waits as
	 * before, with the refusal as the answer.
	 */
	public WorkItem accept(String shareId, Acceptance acceptance, User recipient) {
		TimeEntryShare share = requireAddressed(shareId, recipient);
		if (share.getStatus() == TimeEntryShare.Status.ACCEPTED) {
			return existingCopy(share, recipient);
		}
		if (share.getStatus() != TimeEntryShare.Status.PENDING) {
			throw ApiException.conflict(share.getStatus() == TimeEntryShare.Status.REVOKED
					? "error.time.share.revoked" : "error.time.share.answered");
		}
		TimeEntryShare claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(share.getId())
						.and("status").is(TimeEntryShare.Status.PENDING)),
				new Update().set("status", TimeEntryShare.Status.ACCEPTED).set("decidedAt", clock.instant()),
				FindAndModifyOptions.options().returnNew(true), TimeEntryShare.class);
		if (claimed == null) {
			// Answered or taken back between the read and the claim: decide on what is there now.
			return accept(shareId, acceptance, recipient);
		}
		WorkItem copy;
		try {
			copy = timeTracking.createFromShare(draftOf(claimed, acceptance), claimed.getEntryId(), recipient);
		}
		catch (DuplicateKeyException taken) {
			copy = timeTracking.copyOf(claimed.getEntryId(), recipient.getId());
			if (copy == null) {
				unclaim(claimed);
				throw taken;
			}
		}
		catch (RuntimeException refused) {
			unclaim(claimed);
			throw refused;
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(claimed.getId())),
				new Update().set("copyId", copy.getId()), TimeEntryShare.class);
		User sender = users.findById(claimed.getFromUserId()).orElse(null);
		notifications.notifyTimeShareAccepted(sender, recipient.getDisplayName());
		audit.event(AuditAction.TIME_SHARE_ACCEPTED).actor(recipient).target(sender)
				.meta("workItem", copy.getId()).meta("sharedFrom", claimed.getEntryId())
				.meta("project", copy.getProjectId()).log();
		return copy;
	}

	/** Says no. Nobody is told, and no reason is asked for or kept. */
	public void decline(String shareId, User recipient) {
		TimeEntryShare share = requireAddressed(shareId, recipient);
		if (share.getStatus() == TimeEntryShare.Status.DECLINED) {
			return;
		}
		boolean declined = mongo.updateFirst(Query.query(Criteria.where("_id").is(share.getId())
						.and("status").is(TimeEntryShare.Status.PENDING)),
				new Update().set("status", TimeEntryShare.Status.DECLINED).set("decidedAt", clock.instant()),
				TimeEntryShare.class).getModifiedCount() > 0;
		if (!declined) {
			throw ApiException.conflict(share.getStatus() == TimeEntryShare.Status.REVOKED
					? "error.time.share.revoked" : "error.time.share.answered");
		}
	}

	// --- helpers -------------------------------------------------------------------------------

	private TimeEntryShare requireAddressed(String shareId, User recipient) {
		TimeEntryShare share = ObjectId.isValid(shareId) ? mongo.findById(shareId, TimeEntryShare.class) : null;
		if (share == null) {
			throw ApiException.notFound("timeShare");
		}
		if (!recipient.getId().equals(share.getToUserId())) {
			throw ApiException.forbidden("error.time.share.notRecipient");
		}
		return share;
	}

	private WorkItem existingCopy(TimeEntryShare share, User recipient) {
		WorkItem copy = timeTracking.copyOf(share.getEntryId(), recipient.getId());
		if (copy == null) {
			// Accepted, and the copy deleted since: the invitation has had its answer.
			throw ApiException.conflict("error.time.share.answered");
		}
		return copy;
	}

	private void unclaim(TimeEntryShare claimed) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(claimed.getId())
						.and("status").is(TimeEntryShare.Status.ACCEPTED).and("copyId").exists(false)),
				new Update().set("status", TimeEntryShare.Status.PENDING).unset("decidedAt"), TimeEntryShare.class);
	}

	/** The copy as the invitation describes it, with what the recipient changed. */
	static TimeTrackingService.NewEntry draftOf(TimeEntryShare share, Acceptance acceptance) {
		TimeEntryShare.Snapshot entry = share.getEntry();
		String projectId = share.getProjectId();
		String issueId = entry.getIssueId();
		List<String> tags = entry.getTags();
		if (acceptance != null) {
			if (acceptance.projectId() != null && !acceptance.projectId().equals(projectId)) {
				projectId = acceptance.projectId();
				issueId = null;
			}
			if (acceptance.issueId() != null) {
				issueId = acceptance.issueId().isBlank() ? null : acceptance.issueId();
			}
			if (acceptance.tags() != null) {
				tags = acceptance.tags();
			}
		}
		boolean timed = entry.getStartedAt() != null && entry.getEndedAt() != null;
		return new TimeTrackingService.NewEntry(projectId, issueId, timed ? null : entry.getDurationMinutes(),
				entry.getDate(), entry.getActivityType(), entry.getDescription(),
				timed ? entry.getStartedAt() : null, timed ? entry.getEndedAt() : null, tags, entry.getBillable());
	}

	private List<TimeEntryShare> sharesOf(String entryId) {
		return mongo.find(Query.query(Criteria.where("entryId").is(entryId)).with(NEWEST_FIRST)
				.limit(PAGE_MAX), TimeEntryShare.class);
	}

	/**
	 * The rows as {@code reader} sees them, with names looked up once per page: one read each for
	 * the people, the projects and the issues, and the reach once per project.
	 */
	private List<ShareView> views(List<TimeEntryShare> rows, User reader) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Set<String> personIds = new LinkedHashSet<>();
		Set<String> projectIds = new LinkedHashSet<>();
		Set<String> issueIds = new LinkedHashSet<>();
		for (TimeEntryShare row : rows) {
			personIds.add(row.getFromUserId());
			personIds.add(row.getToUserId());
			projectIds.add(row.getProjectId());
			if (row.getEntry() != null && row.getEntry().getIssueId() != null) {
				issueIds.add(row.getEntry().getIssueId());
			}
		}
		Map<String, String> names = new HashMap<>();
		users.findAllById(personIds).forEach(user -> names.put(user.getId(), user.getDisplayName()));
		Map<String, Issue> issuesById = new HashMap<>();
		issues.findAllById(issueIds).forEach(issue -> issuesById.put(issue.getId(), issue));
		issuesById.values().forEach(issue -> projectIds.add(issue.getProjectId()));
		Map<String, Project> projectsById = new HashMap<>();
		projects.findAllById(projectIds).forEach(project -> projectsById.put(project.getId(), project));
		Map<String, Boolean> visible = new HashMap<>();
		Function<String, Project> seen = projectId -> {
			Project project = projectId == null ? null : projectsById.get(projectId);
			return project != null && visible.computeIfAbsent(projectId,
					id -> projectReach.canSee(project, reader)) ? project : null;
		};

		List<ShareView> out = new ArrayList<>(rows.size());
		for (TimeEntryShare row : rows) {
			boolean sender = reader.getId().equals(row.getFromUserId());
			TimeEntryShare.Snapshot entry = row.getEntry() == null
					? TimeEntryShare.Snapshot.builder().build() : row.getEntry();
			Project project = seen.apply(row.getProjectId());
			Issue issue = entry.getIssueId() == null ? null : issuesById.get(entry.getIssueId());
			boolean issueSeen = issue != null && seen.apply(issue.getProjectId()) != null;
			out.add(new ShareView(row.getId(), row.getStatus(), row.getCreatedAt(), row.getDecidedAt(),
					new Person(row.getFromUserId(), names.get(row.getFromUserId())),
					new Person(row.getToUserId(), names.get(row.getToUserId())),
					sender ? row.getEntryId() : null, sender ? null : row.getCopyId(),
					row.getProjectId(), project == null ? null : project.getName(),
					project == null ? null : project.getKey(), entry.getIssueId(),
					issueSeen ? issue.getReadableId() : null, issueSeen ? issue.getTitle() : null,
					entry.getDate(), entry.getDurationMinutes(), entry.getStartedAt(), entry.getEndedAt(),
					entry.getActivityType(), entry.getDescription(), entry.getBillable(), entry.getTags()));
		}
		return out;
	}
}
