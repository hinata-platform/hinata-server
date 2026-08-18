package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The bundled-e-mail side of watching an issue: changes go in, at most one mail
 * per issue and recipient comes out.
 *
 * <p>The bell and the push fire at the moment of the change — they are glanced
 * at and dismissed. A mail is not: one per edit turns a busy issue into a
 * mailbox flood, and the only lesson a flooded recipient takes is to stop
 * watching. So the mail waits for the editing to settle
 * ({@code quiet-window}), with a ceiling ({@code max-delay}) so continuous
 * editing can never starve it out entirely.
 *
 * <p>Everything that could have changed between queueing and sending is checked
 * again at send time — project access above all, and against the project the
 * issue is in <em>now</em>, not the one it was in when the change was queued. A
 * mail composed half an hour ago must not leak an issue title to someone who was
 * removed from the project twenty minutes ago, nor to someone who still reaches
 * the project the issue has since been moved out of.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueDigestService {

	/**
	 * Hard cap on the entries one bundle may accumulate.
	 *
	 * <p>Fifteen whitelisted fields collapse into at most fifteen lines however
	 * many raw entries there are, so this loses nothing a reader would notice —
	 * what it prevents is a retry-looping automation PATCHing one watched issue a
	 * thousand times inside the ceiling and growing a single document past Mongo's
	 * 16 MB limit, where the push would start throwing on every later change.
	 */
	private static final int MAX_ENTRIES = 500;

	private final MongoTemplate mongo;
	private final IssueMailDigestRepository digests;
	// Repositories rather than IssueService / UserService: this service is reached
	// from NotificationService, which IssueService depends on. Injecting the
	// service back would close the cycle and fail the context at startup with a
	// BeanCurrentlyInCreationException — the same reason ProjectReach gives.
	private final IssueRepository issues;
	private final UserRepository users;
	private final MailService mail;
	private final GatewayService gateway;
	// The same rule the immediate fan-out applies — asked again here because time
	// has passed since the change was queued.
	private final ProjectReach reach;
	private final IssueChangeRenderer renderer;
	private final HinataProperties properties;
	private final Clock clock;

	/**
	 * Adds {@code changes} to the recipient's open bundle for this issue, opening
	 * one if there is none.
	 *
	 * <p>Always a {@code $push}, never read-modify-write: two edits landing in the
	 * same millisecond would otherwise both read an empty bundle and one would
	 * overwrite the other's changes. The unique partial index makes the loser of
	 * the insert race retry into the winner's document rather than create a second
	 * bundle.
	 *
	 * <p>Both deadlines are computed here rather than in the sweep's query, so the
	 * sweep is a range scan on an indexed field instead of an {@code $or} that no
	 * index can serve — see {@link IssueMailDigest#getSoftDueAt()}.
	 *
	 * @return always {@code true} — the caller reads it as "the e-mail is handled,
	 *         don't send one now"
	 */
	public boolean queue(Issue issue, User recipient, List<FieldChange> changes) {
		if (issue == null || recipient == null || changes == null || changes.isEmpty()) {
			return false;
		}
		Instant now = Instant.now(clock);
		HinataProperties.Watch watch = properties.getNotification().getWatch();
		Update update = new Update()
				.setOnInsert("projectId", issue.getProjectId())
				.setOnInsert("firstQueuedAt", now)
				.setOnInsert("createdAt", now)
				// Written explicitly rather than left absent so the partial index has a
				// real value to key on, and so a row is never ambiguous in the shell.
				.setOnInsert("sentAt", null)
				// The ceiling is measured from the first unsent change, so it is set
				// once and never moved; the debounce is measured from the last one.
				.setOnInsert("hardDueAt", now.plus(watch.getMaxDelay()))
				.set("lastQueuedAt", now)
				.set("softDueAt", now.plus(watch.getQuietWindow()))
				.push("changes").each(changes.toArray());
		// The cap rides along in the filter rather than as a $slice on the push:
		// collapsing needs the FIRST oldValue of every field, which a negative
		// $slice is precisely what would throw away.
		Query notFull = openBundle(recipient.getId(), issue.getId())
				.addCriteria(Criteria.where("changes." + MAX_ENTRIES).exists(false));
		if (mongo.updateFirst(notFull, update, IssueMailDigest.class).getMatchedCount() > 0) {
			return true;
		}
		// Nothing matched, which means one of two very different things: there is no
		// open bundle yet, or the one that is there has hit the cap. Only the first
		// deserves a write — asked as a read rather than by letting an upsert insert
		// into the unique index and catching the failure, because a failed write
		// inside a Mongo transaction aborts the whole transaction, and this runs
		// inside a sprint completion and inside an issue move.
		if (mongo.exists(openBundle(recipient.getId(), issue.getId()), IssueMailDigest.class)) {
			log.warn("Change bundle for issue {} / user {} is at its {}-entry cap; "
					+ "dropping further changes until it is sent",
					issue.getId(), recipient.getId(), MAX_ENTRIES);
			return true; // a mail IS coming; it simply will not grow any further
		}
		mongo.upsert(openBundle(recipient.getId(), issue.getId()), update, IssueMailDigest.class);
		return true;
	}

	/**
	 * Drops the recipient's pending bundle for one issue. Called on unwatch: a
	 * mail arriving after someone unsubscribed is exactly the thing that makes
	 * them distrust the unsubscribe.
	 */
	public void discard(String userId, String issueId) {
		if (userId == null || issueId == null) return;
		mongo.remove(openBundle(userId, issueId), IssueMailDigest.class);
	}

	/**
	 * Drops the pending bundles of the named users for one project — used when
	 * their access is revoked. Best-effort hygiene; the send-time access check is
	 * what actually guarantees nothing leaks.
	 *
	 * <p>A null or empty {@code userIds} deletes <em>nothing</em>. The wide delete
	 * lives in {@link #discardAllFor(String)} and has to be asked for by name: on a
	 * destructive operation, "no users" quietly meaning "all users" is a trap that
	 * only ever springs once, the day an {@code Optional.orElse(null)} reaches it.
	 */
	public void discardFor(String projectId, Collection<String> userIds) {
		if (projectId == null || userIds == null || userIds.isEmpty()) return;
		mongo.remove(new Query(Criteria.where("projectId").is(projectId)
				.and("sentAt").is(null)
				.and("userId").in(userIds)), IssueMailDigest.class);
	}

	/**
	 * Drops every pending bundle of a whole project — used when the project itself
	 * goes away, where "everyone" is the honest scope rather than an accident.
	 */
	public void discardAllFor(String projectId) {
		if (projectId == null) return;
		mongo.remove(new Query(Criteria.where("projectId").is(projectId)
				.and("sentAt").is(null)), IssueMailDigest.class);
	}

	/**
	 * Sends every bundle that is due, up to {@code limit} of them.
	 *
	 * <p>Due means the editing has been quiet for {@code quiet-window}, or the
	 * bundle has been open for {@code max-delay} — whichever comes first, each
	 * asked as its own index-backed query (see {@link #due}). The limit keeps a
	 * backlog (an instance that was down for a day) from being loaded into memory
	 * in one go; the next minute picks up the rest.
	 *
	 * <p>Everything the whole batch shares is resolved once, before the loop:
	 * twelve watchers of one issue are twelve entries in the same sweep, and
	 * asking for that issue, its project and the access rule twelve times over is
	 * how a steady-state minute turns into thousands of round trips.
	 *
	 * @return how many mails actually went out
	 */
	public int sweep(int limit) {
		Instant now = Instant.now(clock);
		List<IssueMailDigest> batch = due(now, limit);
		if (batch.isEmpty()) return 0;

		Map<String, Issue> issuesById = byId(issues.findAllById(distinct(batch,
				IssueMailDigest::getIssueId)), Issue::getId);
		Map<String, User> usersById = byId(users.findAllById(distinct(batch,
				IssueMailDigest::getUserId)), User::getId);
		Map<String, Set<String>> visibleByProject = visibleByProject(batch, issuesById);

		int sent = 0;
		for (IssueMailDigest entry : batch) {
			try {
				Issue issue = issuesById.get(entry.getIssueId());
				Set<String> visible = issue == null
						? Set.of()
						: visibleByProject.getOrDefault(issue.getProjectId(), Set.of());
				if (send(entry.getId(), issue, usersById.get(entry.getUserId()),
						visible.contains(entry.getUserId()), now)) {
					sent++;
				}
			}
			catch (RuntimeException ex) {
				// One unreadable row (a template that will not render, a stored value
				// this build can no longer map) must never stop the sweep for everyone
				// else. Logged with the exception, not with getMessage(): on an NPE
				// that would print the word "null" and nothing else.
				log.warn("Sending the change digest {} failed", entry.getId(), ex);
			}
		}
		return sent;
	}

	/**
	 * The bundles whose time has come, oldest deadline first.
	 *
	 * <p>Two queries rather than one {@code $or}, because an {@code $or} across
	 * two fields cannot be served by a single index and — worse — cannot have its
	 * sort provided by one either, which turns every tick into a blocking SORT
	 * over the entire pending backlog. Each half here is equality on
	 * {@code sentAt} followed by a range on the field it sorts by, which is
	 * exactly the shape an IXSCAN with an index-provided sort wants.
	 *
	 * <p>Only the fields the batching needs are read. {@code changes} is
	 * deliberately left out: it is both the fat part of the document and the only
	 * part whose shape can drift (it holds inline {@link FieldChange} records), and
	 * a mapping failure here — outside any per-entry guard — would take down the
	 * sweep for every watcher on the instance, every minute, until someone deleted
	 * the row by hand. It is read inside the guard instead, when the entry is
	 * claimed.
	 */
	private List<IssueMailDigest> due(Instant now, int limit) {
		Map<String, IssueMailDigest> byId = new LinkedHashMap<>();
		collectDue(byId, "softDueAt", now, limit);
		collectDue(byId, "hardDueAt", now, limit);
		return byId.values().stream().limit(limit).toList();
	}

	private void collectDue(Map<String, IssueMailDigest> into, String dueField, Instant now,
			int limit) {
		Query query = new Query(Criteria.where("sentAt").is(null).and(dueField).lte(now))
				.with(Sort.by(Sort.Direction.ASC, dueField))
				.limit(limit);
		query.fields().include("userId").include("issueId").include("projectId");
		for (IssueMailDigest stub : mongo.find(query, IssueMailDigest.class)) {
			into.putIfAbsent(stub.getId(), stub);
		}
	}

	/**
	 * Per live project, which of this batch's recipients may see it — one bulk
	 * question per project instead of one per entry. Keyed by the project the
	 * issue is in now, which is the only one the send-time check may trust.
	 */
	private Map<String, Set<String>> visibleByProject(List<IssueMailDigest> batch,
			Map<String, Issue> issuesById) {
		Map<String, Set<String>> candidates = new LinkedHashMap<>();
		for (IssueMailDigest entry : batch) {
			Issue issue = issuesById.get(entry.getIssueId());
			if (issue == null || issue.getProjectId() == null || entry.getUserId() == null) continue;
			candidates.computeIfAbsent(issue.getProjectId(), key -> new LinkedHashSet<>())
					.add(entry.getUserId());
		}
		Map<String, Set<String>> visible = new HashMap<>();
		candidates.forEach((projectId, userIds) ->
				visible.put(projectId, reach.whoCanSee(projectId, userIds)));
		return visible;
	}

	/**
	 * Claims one bundle and mails it, if it still deserves to be mailed.
	 *
	 * <p>The claim comes first, and it is a conditional {@code findAndModify} on
	 * {@code sentAt == null}: with two instances sweeping the same minute exactly
	 * one wins the update and exactly one mail goes out. Marking before sending
	 * also means a crash mid-send loses a mail rather than repeating it — the
	 * kinder failure of the two. It stays per document however much of the batch
	 * is resolved in bulk around it: the claim <em>is</em> the mutual exclusion.
	 *
	 * <p>{@code issue}, {@code recipient} and {@code visible} were resolved for the
	 * whole batch. The order matters: the issue decides which project the access
	 * answer had to be about, so it is loaded before that answer is used, never
	 * after.
	 *
	 * @return whether a mail was handed to the mail layer
	 */
	private boolean send(String entryId, Issue issue, User recipient, boolean visible, Instant now) {
		IssueMailDigest claimed = mongo.findAndModify(
				new Query(Criteria.where("_id").is(entryId).and("sentAt").is(null)),
				new Update().set("sentAt", now),
				FindAndModifyOptions.options().returnNew(true),
				IssueMailDigest.class);
		if (claimed == null) {
			return false; // another instance got there first
		}
		if (issue == null) {
			return false; // deleted mid-flight. An ARCHIVED issue is NOT dropped: the
			// archival is itself a change the watcher subscribed to hear about, and
			// dropping the bundle would take every edit queued before it along.
		}
		// The issue may have been moved to another project since this was queued.
		// The bundle's own projectId is then a stale claim about a mail whose body
		// is rendered from the live issue, so it is not merely re-checked — it is
		// refused outright, and the access answer above was asked about the live
		// project rather than about the one recorded here.
		if (!Objects.equals(claimed.getProjectId(), issue.getProjectId())) {
			log.debug("Dropping change digest {}: the issue moved to another project",
					claimed.getId());
			return false;
		}
		if (recipient == null || !recipient.isActive()) {
			return false;
		}
		// Up to half an hour has passed since the change. Access is re-decided here,
		// not trusted from queue time.
		if (!visible) {
			log.debug("Dropping change digest {}: recipient can no longer see the project",
					claimed.getId());
			return false;
		}
		// The user may have switched watching off while this was queued.
		if (!preferencesOf(recipient).deliversEmail(NotificationPreferences.WATCHING)) {
			return false;
		}
		List<FieldChange> changes = FieldChange.collapse(claimed.getChanges());
		if (changes.isEmpty()) {
			return false; // everything cancelled itself out — nothing worth a mail
		}
		boolean de = "de".equalsIgnoreCase(recipient.getLocale());
		mail.sendTemplate(recipient.getEmail(), subject(issue, changes.size(), de),
				"email/issue-changes", model(issue, changes, de));
		return true;
	}

	/** "[Hinata] HIN-42: 3 Änderungen" — the issue key first, so a threaded
	 *  mailbox groups a watched issue's mails together. */
	private String subject(Issue issue, int count, boolean de) {
		String noun = de
				? (count == 1 ? "Änderung" : "Änderungen")
				: (count == 1 ? "change" : "changes");
		return NotificationService.SUBJECT_PREFIX + issue.getReadableId() + ": " + count + " " + noun;
	}

	private Map<String, Object> model(Issue issue, List<FieldChange> changes, boolean de) {
		Map<String, Object> model = new HashMap<>();
		model.put("locale", de ? "de" : "en");
		model.put("headline", issue.getReadableId() + " · " + issue.getTitle());
		// Rendered once. Every line can cost a point read to resolve a display name,
		// a sprint name or a parent's key, and the preheader is the same lines
		// squeezed onto one — asking the renderer twice would pay for all of them
		// twice, per recipient.
		List<IssueChangeRenderer.Line> lines = renderer.lines(changes, de);
		model.put("preheader", renderer.summaryOf(lines));
		model.put("lines", new ArrayList<>(lines));
		// The recipient's access was just re-checked, so the CTA is theirs to
		// follow; it is relayed through Hinata Connect like every other mail link so
		// the native app intercepts it as a Universal/App Link. The button's label
		// is left to the layout's `email.cta.open`, like every other templated mail.
		model.put("ctaLink", gateway.relayLink("/issues/" + issue.getReadableId(), null));
		return model;
	}

	private NotificationPreferences preferencesOf(User user) {
		NotificationPreferences prefs = user.getNotificationPreferences();
		return (prefs == null ? NotificationPreferences.defaults() : prefs).sanitized();
	}

	/**
	 * Whether a mail is still waiting to go out to this recipient about this
	 * issue. Reads through the unique partial index, so it is a point lookup.
	 */
	public boolean hasPending(String userId, String issueId) {
		return !digests.findByUserIdAndIssueIdAndSentAtIsNull(userId, issueId).isEmpty();
	}

	// --- helpers ---------------------------------------------------------------

	/** The one open bundle of a recipient for an issue — the unique partial index's key. */
	private static Query openBundle(String userId, String issueId) {
		return new Query(Criteria.where("userId").is(userId)
				.and("issueId").is(issueId)
				.and("sentAt").is(null));
	}

	private static List<String> distinct(List<IssueMailDigest> batch,
			java.util.function.Function<IssueMailDigest, String> id) {
		Set<String> ids = new LinkedHashSet<>();
		for (IssueMailDigest entry : batch) {
			String value = id.apply(entry);
			if (value != null) ids.add(value);
		}
		return List.copyOf(ids);
	}

	private static <T> Map<String, T> byId(Iterable<T> values,
			java.util.function.Function<T, String> id) {
		Map<String, T> byId = new HashMap<>();
		values.forEach(value -> byId.put(id.apply(value), value));
		return byId;
	}
}
