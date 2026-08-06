package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Freeze: content that is preserved and unreachable, to everyone, until a named
 * person releases it.
 *
 * <h2>Why this is not part of the per-viewer access mechanism</h2>
 *
 * <p>It looks like one — both subtract things from what a reader gets — and
 * merging them would be a mistake, because they answer questions with opposite
 * shapes. Access is parameterised by the viewer; freeze is parameterised by
 * nothing. Access fails <em>open</em> when it goes wrong (you see more than you
 * should, which is bad and recoverable); freeze has to fail <em>closed</em>.
 * Above all, an admin <em>bypasses</em> access — {@code SearchService.Scope} and
 * {@link com.ahmadre.hinata.article.ArticleVisibility#criteria} both return
 * {@code null} to say exactly that — and an admin does <em>not</em> bypass a
 * freeze.
 *
 * <p>That {@code null} contract is the decisive incompatibility. {@code null}
 * there means "no restriction", and {@code SearchService.and} drops a null access
 * filter entirely; folding freeze into it would make one value mean two things
 * and the first caller to forget composes to no filter at all. So freeze is a
 * separate collaborator whose {@link #exclusion} <em>never</em> returns null, and
 * it is {@code andOperator}-composed alongside access rather than inside it.
 *
 * <p>What is copied from that mechanism is its three structural lessons: one
 * definition rather than a copy per caller, a query form as well as a predicate
 * so the filter travels into Mongo instead of sifting a capped window afterwards,
 * and resolution once rather than per category. Freeze needs a third form access
 * does not have — {@link #assertReadable}, which throws — because most read paths
 * are by-id lookups with no {@code Criteria} to narrow.
 *
 * <h2>Why the set is held in memory, and what happens when it cannot be</h2>
 *
 * <p>A registry cannot be joined into a plain {@code find}, and post-filtering is
 * off the table for the reason above. So the whole active set is held in a
 * volatile snapshot, refreshed on startup, after every write, and on a timer — the
 * same shape {@code ModerationPolicy} already uses for the resolved policy block.
 * That is affordable because the set is empty in every healthy install:
 * {@link #exclusion} on an empty set is a {@code $nin: []}, which matches
 * everything, so a workspace with nothing frozen runs byte-for-byte the queries it
 * always ran.
 *
 * <h2>A freeze is not instant across replicas, and this is the bound</h2>
 *
 * <p>The snapshot is <b>per JVM</b>. When the product runs as more than one
 * replica — a scaled service in Compose or Portainer, two containers behind a
 * proxy — a freeze raised on instance A is enforced on A immediately, because A
 * refreshes at the end of its own write, and on every other instance only when
 * that instance next polls. {@link #refreshPeriodically} is that poll, and
 * {@code hinata.moderation.freeze-refresh-interval} (default 30s) is therefore
 * <b>the worst-case window in which an instance that did not perform the write
 * still serves the frozen content</b>. It is not zero and this class must not be
 * read as claiming it is.
 *
 * <p>The alternative that would make it zero is a message bus, and this product
 * has none — {@code SettingsService} holds its resolved settings the same
 * in-process way and has the same limitation, so polling is the established shape
 * here rather than a shortcut taken to avoid one. What polling buys over reading
 * the registry per request is the reason the snapshot exists at all: this is
 * consulted on every read of every entity and every byte in the product, and a
 * database round trip per read is not a cost the product can carry to answer a
 * question whose answer is "no" in every install that has never had an incident.
 *
 * <p>Two things bound the damage of the window. The freeze is written to Mongo
 * <em>before</em> the refresh, so no instance is ever more than one interval
 * behind the database rather than behind another instance's memory; and the
 * failure inside the window is that content stays readable, which is what it was
 * a moment earlier — not that a released freeze keeps applying.
 *
 * <p><b>The single most important line in this class is {@link Snapshot#loaded}.</b>
 * It distinguishes "loaded, and nothing is frozen" from "we do not know". If the
 * registry has never been read, every guard here answers 503 rather than "nothing
 * is frozen" — a mechanism whose failure mode is silently serving the material it
 * exists to withhold is worse than one that stops. The same applies to
 * {@link #MAX_FROZEN}: over the cap the snapshot is never truncated, because a
 * truncated set is a set that answers "not frozen" for real rows.
 *
 * <h2>A failed refresh is not a failed first load, and they must not be treated alike</h2>
 *
 * <p>Fail-closed on the <em>first</em> load is not negotiable and is exactly the
 * paragraph above: with no set at all, "nothing is frozen" is a guess, and the cost
 * of guessing wrong is serving the material. But once a set <em>has</em> been read,
 * a refresh that fails is a different question with a different answer. The last
 * loaded set is still almost certainly correct — freezes are single-digit events in
 * the life of an install, so the probability that one was raised inside the seconds
 * a transient Mongo error lasted is close to zero — while dropping it takes the
 * comment feed, the search, the issue list, the board and every byte in the product
 * to 503 for everybody. That trades a hypothetical miss for a certain outage, and it
 * is the wrong way round.
 *
 * <p>So {@link #degrade} keeps the last loaded snapshot across a failing refresh and
 * drops it only after <b>{@link #MAX_REFRESH_FAILURES} consecutive failures</b> —
 * with the default 30s interval, five minutes of a registry nobody can read. Every
 * one of those attempts logs at {@code ERROR} naming the last good read, so a stuck
 * registry is loud from the first failure rather than only at the end.
 *
 * <p><b>The window this opens, stated plainly.</b> While a refresh is failing, this
 * instance enforces the set as of {@code loadedAt} and nothing newer. A freeze
 * raised elsewhere in that period — by an operator, by a report trigger, or by this
 * very instance if its own post-write refresh was the call that failed — is
 * <em>not</em> enforced here until a refresh succeeds, or until the staleness budget
 * runs out and everything fails closed. The bound is
 * {@code MAX_REFRESH_FAILURES × hinata.moderation.freeze-refresh-interval}. That is
 * the same kind of window as the per-JVM one above, deliberately: both say "this
 * instance is behind the database by at most N", and both end in the database
 * winning rather than in a freeze being lost.
 *
 * <p><b>What no state model can do</b> is make a <em>forgotten</em> read path fail
 * closed. A registry and a per-entity flag are equally silent when a new endpoint
 * never asks. That is what the structural checks in {@code ModerationWiringTest}
 * are for, and it is why they are not optional garnish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrozenContentService {

	/**
	 * Ceiling on the active set.
	 *
	 * <p>Freeze is rare by construction — it is raised by a hash-programme match or
	 * an urgent report, both of which should be single-digit events in the life of
	 * an install. A number in the thousands means something is wrong (a script, a
	 * mass-report campaign, a bug in a trigger), and the honest response to that is
	 * to stop and say so, not to keep growing a {@code $nin} inside every query in
	 * the product.
	 */
	public static final int MAX_FROZEN = 5_000;

	/**
	 * How many refreshes in a row may fail before the last loaded set is dropped and
	 * every guard goes back to answering 503.
	 *
	 * <p>The number is a staleness budget expressed in refresh intervals rather than
	 * in seconds, because the interval is the operator's knob and the two have to move
	 * together: at the 30s default this is five minutes, and an install that polls
	 * every 5s gets fifty seconds of tolerance from the same constant. Ten is chosen
	 * to be comfortably longer than a Mongo failover or a rolling restart — the
	 * failures this is meant to ride out — and comfortably shorter than a shift, so a
	 * registry that is genuinely broken stops the product while somebody is still
	 * around to notice.
	 */
	public static final int MAX_REFRESH_FAILURES = 10;

	private final FrozenContentRepository repository;
	private final AuditService audit;
	/**
	 * Resolves the bytes a target owns, so no caller has to remember to.
	 *
	 * <p>Held here rather than asked at each call site because forgetting is exactly
	 * what happened: the admin hand-freeze passed {@code List.of()} and therefore
	 * froze a row and not one byte. A collaborator on this class makes the complete
	 * freeze the only freeze there is.
	 */
	private final FrozenObjectKeys objectKeys;

	private volatile Snapshot snapshot = Snapshot.unknown();

	/**
	 * Consecutive failed refreshes since the last good one.
	 *
	 * <p>Atomic rather than volatile-int because {@link #degrade} increments it, and
	 * two callers can be in there at once: the scheduled poll and the refresh at the
	 * end of a {@link #freeze} both run this method. A lost increment would silently
	 * extend the staleness budget past its bound, which is the one thing the counter
	 * exists to prevent.
	 */
	private final AtomicInteger consecutiveFailures = new AtomicInteger();

	/**
	 * The active set, as one immutable value.
	 *
	 * <p>One object rather than a field per kind so a refresh is a single volatile
	 * write: a reader can never observe the issue ids of one generation against the
	 * object keys of another, which is the interleaving that would let a freeze be
	 * half applied for the duration of a request.
	 *
	 * @param loaded   whether this snapshot reflects a successful read. {@code false}
	 *                 is not "nothing is frozen" — it is "the question cannot be
	 *                 answered", and every guard turns it into a 503.
	 * @param loadedAt when that read happened, or {@code null} when there has not been
	 *                 one. Carried so the ERROR logged on every failed refresh can name
	 *                 how old the set being served is — "the registry is unreachable"
	 *                 is not actionable on its own, and "and we are serving the set
	 *                 from 14:02" is
	 */
	private record Snapshot(boolean loaded, Map<FrozenTargetType, Set<String>> ids,
			Instant loadedAt) {

		static Snapshot unknown() {
			return new Snapshot(false, Map.of(), null);
		}

		Set<String> of(FrozenTargetType type) {
			return ids.getOrDefault(type, Set.of());
		}

		boolean empty() {
			return ids.values().stream().allMatch(Set::isEmpty);
		}
	}

	// --- lifecycle -------------------------------------------------------------

	/**
	 * Loads the registry once the context is up.
	 *
	 * <p>{@link ApplicationReadyEvent} rather than {@code @PostConstruct}: the read
	 * needs Mongo, and a bean that queries during construction is a bean that
	 * changes what "the application failed to start" means for an unrelated
	 * configuration error.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void refresh() {
		try {
			List<FrozenContent> active = repository.findByUnfrozenAtIsNull();
			if (active.size() > MAX_FROZEN) {
				// Deliberately NOT truncated. A partial set answers "not frozen" for
				// every row past the cap, which is the one answer this class must
				// never give by accident. Routed through degrade() rather than
				// straight to unknown() because the honest description of the state
				// is the same as an unreadable registry's — "this read produced no
				// usable set" — and it is a state an operator has to be given time to
				// act on rather than one that takes the product down at the instant a
				// runaway trigger crosses the line.
				degrade(String.format(
						"the registry holds %d active rows, above the %d cap, and a truncated set "
								+ "would answer \"not frozen\" for every row past it",
						active.size(), MAX_FROZEN));
				return;
			}
			Map<FrozenTargetType, Set<String>> ids = new EnumMap<>(FrozenTargetType.class);
			for (FrozenContent row : active) {
				if (row.getTargetType() == null || row.getTargetId() == null) {
					continue;
				}
				ids.computeIfAbsent(row.getTargetType(), key -> new HashSet<>()).add(row.getTargetId());
			}
			snapshot = new Snapshot(true, Map.copyOf(ids), Instant.now());
			// Reset after the volatile write, never before: a reader that saw the
			// counter at zero while the old snapshot was still published would be
			// told the set is fresh when it is not.
			int recovered = consecutiveFailures.getAndSet(0);
			if (recovered > 0) {
				log.error("The frozen-content registry is readable again after {} failed refresh(es)",
						recovered);
			}
			if (!active.isEmpty()) {
				log.warn("Frozen-content registry loaded: {} active freeze(s)", active.size());
			}
		}
		catch (RuntimeException ex) {
			degrade("the registry could not be read: " + ex);
		}
	}

	/**
	 * What a refresh that produced no usable set does to the one already published.
	 *
	 * <p>Three outcomes, and the split between them is the whole point — see this
	 * class's javadoc for the argument.
	 *
	 * <ol>
	 *   <li><b>Nothing loaded yet.</b> Stay unknown; every guard answers 503. There is
	 *       no set to keep and "nothing is frozen" would be a guess.</li>
	 *   <li><b>Within the staleness budget.</b> Keep the last loaded set. It is almost
	 *       certainly still correct, and dropping it would trade a hypothetical miss
	 *       for a certain product-wide outage.</li>
	 *   <li><b>Budget exhausted.</b> Drop it. Past
	 *       {@link #MAX_REFRESH_FAILURES} intervals the set is no longer "a moment
	 *       old", and a mechanism that keeps serving from a snapshot it can no longer
	 *       confirm has stopped being a guard.</li>
	 * </ol>
	 *
	 * <p>Every branch logs at {@code ERROR}, including the ones that keep working. A
	 * degraded freeze registry is not a warning: nothing else in the product will
	 * report it, the symptom is invisible by construction, and the failure it leads to
	 * is the one this package exists to prevent.
	 */
	private void degrade(String cause) {
		int failures = consecutiveFailures.incrementAndGet();
		Snapshot current = snapshot;
		if (!current.loaded()) {
			log.error("Could not load the frozen-content registry and there is no earlier set to "
					+ "fall back on — content reads fail closed. Consecutive failures: {}. Cause: {}",
					failures, cause);
			return;
		}
		if (failures >= MAX_REFRESH_FAILURES) {
			log.error("The frozen-content registry has failed {} consecutive refreshes since the "
					+ "last good read at {} — dropping the stale set; content reads now fail closed "
					+ "until it can be read again. Cause: {}",
					failures, current.loadedAt(), cause);
			snapshot = Snapshot.unknown();
			return;
		}
		log.error("Refresh of the frozen-content registry failed ({} of {} before the set is "
				+ "dropped) — still enforcing the set loaded at {}, so a freeze raised since then "
				+ "is NOT enforced on this instance. Cause: {}",
				failures, MAX_REFRESH_FAILURES, current.loadedAt(), cause);
	}

	/**
	 * Re-reads the registry on a timer, so an instance learns about a freeze it did
	 * not perform.
	 *
	 * <p>This is the <b>only</b> thing that makes the feature work behind more than
	 * one replica, and the interval is the exposure window — see this class's
	 * javadoc, which states that bound rather than implying a freeze is instant. The
	 * shape follows {@code MediaGarbageCollector}, {@code AuditRetentionJob} and
	 * {@code WeeklyDigestJob}; {@code @EnableScheduling} is already on the
	 * application class.
	 *
	 * <p>{@code fixedDelay} and not {@code fixedRate}: a slow or failing read must
	 * not queue a second one behind it, because the recovery path for a database
	 * that has just come back is the one where a pile-up would hurt. The initial
	 * delay is one interval so the first load stays
	 * {@link ApplicationReadyEvent}'s — a scheduled read firing during context
	 * refresh is what that listener exists to avoid.
	 *
	 * <p>Separate from {@link #refresh} rather than a second annotation on it so the
	 * scheduling decision has somewhere to be explained, and so a test can find the
	 * poll by its annotation instead of trusting that one is configured.
	 */
	@Scheduled(
			fixedDelayString = "${hinata.moderation.freeze-refresh-interval:30s}",
			initialDelayString = "${hinata.moderation.freeze-refresh-interval:30s}")
	public void refreshPeriodically() {
		refresh();
	}

	// --- asking ----------------------------------------------------------------

	/** Whether anything at all is frozen, so a caller can skip the work when nothing is. */
	public boolean anythingFrozen() {
		return !require().empty();
	}

	/** Whether [id] of [type] is frozen. */
	public boolean isFrozen(FrozenTargetType type, String id) {
		return id != null && require().of(type).contains(id);
	}

	/** Whether the object at [objectKey] is frozen. */
	public boolean isFrozenObject(String objectKey) {
		return isFrozen(FrozenTargetType.OBJECT, objectKey);
	}

	/**
	 * Refuses the read when [id] is frozen.
	 *
	 * <p>404 and not 403, for the reason {@code ArticleController} gives about
	 * articles a viewer may not see: a distinguishable status confirms the item
	 * exists. Here it would confirm it to the author of frozen material, which is
	 * the one person who must not learn that their upload was matched.
	 *
	 * @param entityKey names an {@code entity.*} message so the 404 reads exactly
	 *                  like the ordinary one for that kind of thing
	 */
	public void assertReadable(FrozenTargetType type, String id, String entityKey) {
		if (isFrozen(type, id)) {
			throw ApiException.notFound(entityKey);
		}
	}

	/** The same for stored bytes. */
	public void assertObjectReadable(String objectKey) {
		if (isFrozenObject(objectKey)) {
			throw ApiException.notFound("media");
		}
	}

	/**
	 * Refuses a <em>destructive</em> operation on frozen content.
	 *
	 * <p>The mirror image of {@link #assertReadable}, and deliberately the opposite
	 * shape in both respects. It says <b>409 and not 404</b>, because the actor here
	 * is an administrator or a project lead performing a delete they are otherwise
	 * entitled to, and answering "no such issue" to someone who is looking straight
	 * at it produces a bug report rather than a decision. And it says <b>why</b>,
	 * where a read says nothing: this is the paragraph in {@link FrozenContent}'s
	 * javadoc — "a row here is what lets the deletion and garbage-collection paths
	 * refuse" — and a refusal nobody can explain is one somebody works around by
	 * deleting the parent instead.
	 *
	 * <p>The message names preservation pending review and not a match, a category
	 * or a reporter. That is the same line {@code AdminModerationController} draws:
	 * an operator needs to know the content is held, and nobody needs to be told
	 * what it was held for.
	 *
	 * <p>Refuse rather than skip, which is the whole of the harm model here. A
	 * cascade that quietly stepped over the frozen row would delete everything
	 * around it and leave a comment with no issue, an attachment with no ticket —
	 * evidence that is technically present and practically unusable. The one
	 * exception is {@code StorageService.delete}, which skips: it is called from
	 * three best-effort housekeeping paths that must not turn a nightly job into a
	 * failed one, and it is the last line of defence rather than the first.
	 *
	 * @throws ApiException 409 when [id] of [type] is frozen
	 */
	public void assertNotFrozen(FrozenTargetType type, String id) {
		if (isFrozen(type, id)) {
			throw new ApiException(HttpStatus.CONFLICT, "error.moderation.frozenPreserved");
		}
	}

	/** As {@link #assertNotFrozen}, for a set of candidates of one kind. */
	public void assertNoneFrozen(FrozenTargetType type, Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		Set<String> active = require().of(type);
		if (active.isEmpty()) {
			return;
		}
		for (String id : ids) {
			if (id != null && active.contains(id)) {
				throw new ApiException(HttpStatus.CONFLICT, "error.moderation.frozenPreserved");
			}
		}
	}

	/** As {@link #assertNotFrozen}, for stored bytes. */
	public void assertNoObjectFrozen(Collection<String> objectKeys) {
		assertNoneFrozen(FrozenTargetType.OBJECT, objectKeys);
	}

	/**
	 * The frozen ids of [type], for a caller that filters a collection it already
	 * holds. Never null.
	 */
	public Set<String> frozenIds(FrozenTargetType type) {
		return require().of(type);
	}

	/**
	 * The freeze filter for a Mongo query: [field] must not be one of the frozen
	 * ids of [type].
	 *
	 * <p><b>Never null</b>, unlike the access filters it is composed with. A null
	 * there means "an admin, no restriction" and is dropped by the composer; the
	 * same convention here would make freeze evaporate for precisely the account it
	 * most has to apply to.
	 *
	 * <p>An empty set is left as an empty {@code $nin} rather than special-cased
	 * away — it matches every document, so an install with nothing frozen executes
	 * the query it always did. That is the same argument
	 * {@code IssueCommentRepository} makes for splitting the blocked-author reads,
	 * arrived at from the other direction: there the empty case avoids a criterion,
	 * here it is free.
	 */
	public Criteria exclusion(String field, FrozenTargetType type) {
		return Criteria.where(field).nin(require().of(type));
	}

	/**
	 * The snapshot, or a 503 when there is not one.
	 *
	 * <p>Every public read goes through here. That is the whole fail-closed
	 * contract in one place, rather than a null check each caller could get right
	 * in five places and wrong in the sixth.
	 */
	private Snapshot require() {
		Snapshot current = snapshot;
		if (!current.loaded()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"error.moderation.frozenUnavailable");
		}
		return current;
	}

	// --- freezing --------------------------------------------------------------

	/**
	 * What to freeze.
	 *
	 * @param objectKeys extra stored objects to freeze alongside the ones
	 *                   {@link FrozenObjectKeys} finds on its own. Normally empty:
	 *                   {@link #freeze} resolves the target's bytes itself, precisely
	 *                   so that a caller passing nothing still freezes everything.
	 *                   Each key becomes its own {@link FrozenTargetType#OBJECT} row,
	 *                   because the byte guard is a set lookup on the key and cannot
	 *                   walk back to an entity
	 * @param actor      the admin who did it by hand, or {@code null} when a trigger
	 *                   raised it
	 */
	public record Request(FrozenTargetType targetType, String targetId, String contextId,
			Collection<String> objectKeys, ModerationCategory category, String reportId,
			String reporterId, User actor, String reason) {
	}

	/**
	 * Freezes a target, and every object it owns.
	 *
	 * <p>Idempotent: freezing something already frozen returns the standing row
	 * rather than failing. Two people reporting the same comment within seconds of
	 * each other is the normal case, not the edge one — an urgent report notifies
	 * every admin at once — and a second reporter getting a 409 would mean the
	 * product refused the notice it most wanted.
	 *
	 * <p>Deliberately <b>not</b> {@code @Transactional}. This is called from
	 * {@code ContentReportService.file}, which also runs the moderation gate on the
	 * reporter's note; a gate inside a transaction rolls back the very row that
	 * explains why it rolled back, which is what {@code MongoModerationRecorder}'s
	 * javadoc forbids and what {@code SprintService.start} was fixed for. The
	 * ordering rather than the transaction is what makes this safe: freeze first,
	 * then save the report. A freeze with no report is safe and visible; a report
	 * with no freeze leaves the content up.
	 */
	public FrozenContent freeze(Request request) {
		Instant now = Instant.now();
		// Resolved here rather than trusted from the caller. Both halves matter: the
		// union means a caller that already knows a key (the report path, holding the
		// entity) loses nothing, and the resolution means a caller that knows none
		// (the admin hand-freeze) no longer freezes a row and zero bytes.
		List<String> owned = ownedKeys(request);
		FrozenContent row;
		// The refresh is in a finally because a half-written freeze is the worst of
		// the three outcomes: the target row is already persisted, so the database
		// says frozen, while the in-memory snapshot every read path consults still
		// says readable — and the caller swallows the failure. Refreshing whatever
		// did get written keeps the two from disagreeing until the next restart.
		//
		// If THIS refresh is itself the one that fails, degrade() keeps the previous
		// set and the freeze just written is not enforced here until a later poll
		// succeeds. That is the window named in the class javadoc, and it is the same
		// window every other replica is already in — which is why the freeze goes to
		// the database first and the snapshot second.
		try {
			FrozenContent target = repository.findByTargetTypeAndTargetId(
							request.targetType(), request.targetId())
					.filter(FrozenContent::active)
					.orElse(null);
			// An already-standing freeze still has its object list rewritten. An
			// operator who freezes the same issue a second time is usually doing it
			// *because* something was missed — an attachment added since, or a body
			// edited to embed an image — and returning the old row unchanged would
			// make the one gesture that fixes that a no-op.
			row = target == null ? upsert(request, owned, now) : widen(target, owned, now);
			for (String objectKey : owned) {
				repository.findByTargetTypeAndTargetId(FrozenTargetType.OBJECT, objectKey)
						.filter(FrozenContent::active)
						.orElseGet(() -> upsert(new Request(FrozenTargetType.OBJECT, objectKey,
								request.targetId(), List.of(), request.category(), request.reportId(),
								request.reporterId(), request.actor(), request.reason()),
								List.of(), now));
			}
		}
		finally {
			refresh();
		}
		audit.event(AuditAction.CONTENT_FROZEN)
				.actor(request.actor())
				.target(request.targetId(), request.targetType().name())
				.meta("targetType", request.targetType().name())
				.meta("category", request.category() == null ? null : request.category().name())
				.meta("reportId", request.reportId())
				.meta("reason", request.reason())
				.meta("objectKeys", String.valueOf(owned.size()))
				// The DSA Art. 17 obligation, in the log an operator answers from. Null
				// today for every freeze, because nothing in this product issues the
				// statement — see FrozenContent.statementIssuedAt. Carried as null
				// rather than omitted so the day a notice path exists the log
				// distinguishes the freezes that got one from the freezes that did not,
				// including retrospectively.
				.meta("statementIssuedAt",
						row.getStatementIssuedAt() == null ? null : row.getStatementIssuedAt().toString())
				.log();
		if (row.getStatementIssuedAt() == null) {
			log.warn("Froze {} {} without a statement of reasons to its author — DSA Art. 17 owes "
					+ "one and no code path in this product issues it. The freeze row records the "
					+ "omission; discharging the obligation is an operator action.",
					request.targetType(), request.targetId());
		}
		return row;
	}

	/**
	 * Every stored object this freeze covers: what the caller named, plus what the
	 * target actually owns.
	 *
	 * <p>Never throws. {@link FrozenObjectKeys} already swallows its own lookup
	 * failures, and the argument is the same one step up: a freeze that cannot
	 * enumerate the bytes still has to reach the row, because a partial restriction
	 * beats none.
	 */
	private List<String> ownedKeys(Request request) {
		Set<String> all = new LinkedHashSet<>(keys(request.objectKeys()));
		all.addAll(objectKeys.of(request.targetType(), request.targetId(), request.contextId()));
		return List.copyOf(all);
	}

	/**
	 * Adds newly discovered objects to a freeze that is already standing.
	 *
	 * <p>Only ever adds. Dropping a key that a re-resolution no longer finds — an
	 * image removed from the body since — would release bytes the freeze is holding,
	 * by a path with no note and no audit entry, which is precisely what
	 * {@link #unfreeze} exists to be the only way to do.
	 */
	private FrozenContent widen(FrozenContent row, List<String> owned, Instant now) {
		Set<String> merged = new LinkedHashSet<>(keys(row.getObjectKeys()));
		if (!merged.addAll(owned)) {
			return row;
		}
		row.setObjectKeys(List.copyOf(merged));
		log.warn("Widened the standing freeze on {} {} to {} stored object(s) at {}",
				row.getTargetType(), row.getTargetId(), merged.size(), now);
		return repository.save(row);
	}

	/**
	 * Writes or revives one row.
	 *
	 * <p>Reviving a released row rather than inserting beside it: the unique index
	 * on (type, id) is what makes concurrent freezes idempotent, and a second
	 * document for the same target would violate it. The previous release stays
	 * readable in the audit log, which is where the history belongs.
	 */
	private FrozenContent upsert(Request request, List<String> owned, Instant now) {
		FrozenContent row = repository
				.findByTargetTypeAndTargetId(request.targetType(), request.targetId())
				.orElseGet(() -> FrozenContent.builder()
						.targetType(request.targetType())
						.targetId(request.targetId())
						.build());
		row.setContextId(request.contextId());
		row.setObjectKeys(List.copyOf(owned));
		// Deliberately NOT assigned. FrozenContent.statementIssuedAt records when the
		// author was given a DSA Art. 17 statement of reasons, and this product issues
		// none — so the honest record is no data, not a constant claiming there is
		// some. A revived row is cleared for the same reason the release fields below
		// are: it is a new restriction and it has had no notice of its own.
		row.setStatementIssuedAt(null);
		row.setCategory(request.category());
		row.setReportId(request.reportId());
		row.setReporterId(request.reporterId());
		row.setFrozenBy(request.actor() == null ? null : request.actor().getId());
		row.setFrozenAt(now);
		row.setReason(request.reason());
		row.setUnfrozenAt(null);
		row.setUnfrozenBy(null);
		row.setUnfreezeNote(null);
		return repository.save(row);
	}

	/**
	 * Releases a freeze.
	 *
	 * <p>A note is mandatory and that is not paperwork. Nobody releasing a freeze
	 * has seen the content — that is the point of the mechanism — so an unfreeze is
	 * never a judgement on the merits. It is an administrative correction: the
	 * wrong target id, a demonstrably malicious reporter, an authority that has
	 * come back. The note is where that correction is stated, and it is the only
	 * thing an appeal months later can be answered from.
	 *
	 * @throws ApiException 400 without a note, 404 when nothing is frozen there
	 */
	public FrozenContent unfreeze(User admin, FrozenTargetType type, String targetId, String note) {
		String reason = note == null ? null : note.trim();
		if (reason == null || reason.isBlank()) {
			throw ApiException.badRequest("error.moderation.unfreezeNoteRequired");
		}
		FrozenContent row = repository.findByTargetTypeAndTargetId(type, targetId)
				.filter(FrozenContent::active)
				.orElseThrow(() -> ApiException.notFound("frozenContent"));
		List<FrozenContent> released = new ArrayList<>();
		released.add(row);
		// The object rows this target owns go with it, or the entity comes back and
		// its images stay 404 — a half-released freeze looks to everyone like the
		// content is broken rather than restricted.
		for (String objectKey : keys(row.getObjectKeys())) {
			repository.findByTargetTypeAndTargetId(FrozenTargetType.OBJECT, objectKey)
					.filter(FrozenContent::active)
					.ifPresent(released::add);
		}
		Instant now = Instant.now();
		for (FrozenContent each : released) {
			each.setUnfrozenAt(now);
			each.setUnfrozenBy(admin == null ? null : admin.getId());
			each.setUnfreezeNote(reason);
			repository.save(each);
		}
		refresh();
		audit.event(AuditAction.CONTENT_UNFROZEN)
				.actor(admin)
				.target(targetId, type.name())
				.meta("targetType", type.name())
				.meta("reportId", row.getReportId())
				.meta("note", reason)
				.log();
		return row;
	}

	/**
	 * Links a freeze to the report row that was saved after it.
	 *
	 * <p>A second write rather than a field on {@link #freeze}, because the ordering
	 * demands it: the freeze happens <em>before</em> the report is saved, so the
	 * report has no id yet. That order is not negotiable — freezing and then failing
	 * to file leaves content unreachable with no report, which is safe; filing and
	 * then failing to freeze leaves suspected material visible, which is the
	 * outcome the whole mechanism exists to prevent.
	 *
	 * <p>Best-effort, and losing it costs a cross-reference rather than the freeze:
	 * {@code reporterId} is already on the row, so an unlinked freeze is still
	 * attributable to the account that caused it.
	 */
	public void attachReport(FrozenContent row, String reportId) {
		if (row == null || reportId == null || reportId.equals(row.getReportId())) {
			return;
		}
		try {
			row.setReportId(reportId);
			repository.save(row);
		}
		catch (RuntimeException ex) {
			log.warn("Could not link freeze {} to report {}: {}", row.getId(), reportId, ex.toString());
		}
	}

	/** The standing freeze on a target, if there is one. */
	public java.util.Optional<FrozenContent> find(FrozenTargetType type, String targetId) {
		return repository.findByTargetTypeAndTargetId(type, targetId).filter(FrozenContent::active);
	}

	private static List<String> keys(Collection<String> objectKeys) {
		if (objectKeys == null) {
			return List.of();
		}
		return objectKeys.stream().filter(key -> key != null && !key.isBlank()).distinct().toList();
	}
}
