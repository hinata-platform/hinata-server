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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * volatile snapshot, refreshed on startup and after every write — the same shape
 * {@code ModerationPolicy} already uses for the resolved policy block. That is
 * affordable because the set is empty in every healthy install: {@link #exclusion}
 * on an empty set is a {@code $nin: []}, which matches everything, so a workspace
 * with nothing frozen runs byte-for-byte the queries it always ran.
 *
 * <p><b>The single most important line in this class is {@link Snapshot#loaded}.</b>
 * It distinguishes "loaded, and nothing is frozen" from "we do not know". If the
 * registry cannot be read, every guard here answers 503 rather than "nothing is
 * frozen" — a mechanism whose failure mode is silently serving the material it
 * exists to withhold is worse than one that stops. The same applies to
 * {@link #MAX_FROZEN}: over the cap the snapshot fails rather than truncating,
 * because a truncated set is a set that answers "not frozen" for real rows.
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

	private final FrozenContentRepository repository;
	private final AuditService audit;

	private volatile Snapshot snapshot = Snapshot.unknown();

	/**
	 * The active set, as one immutable value.
	 *
	 * <p>One object rather than a field per kind so a refresh is a single volatile
	 * write: a reader can never observe the issue ids of one generation against the
	 * object keys of another, which is the interleaving that would let a freeze be
	 * half applied for the duration of a request.
	 *
	 * @param loaded whether this snapshot reflects a successful read. {@code false}
	 *               is not "nothing is frozen" — it is "the question cannot be
	 *               answered", and every guard turns it into a 503.
	 */
	private record Snapshot(boolean loaded, Map<FrozenTargetType, Set<String>> ids) {

		static Snapshot unknown() {
			return new Snapshot(false, Map.of());
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
				// never give by accident.
				log.error("The frozen-content registry holds {} active rows, above the {} cap — "
						+ "refusing to load a partial set. Content reads will fail closed until an "
						+ "administrator resolves this.", active.size(), MAX_FROZEN);
				snapshot = Snapshot.unknown();
				return;
			}
			Map<FrozenTargetType, Set<String>> ids = new EnumMap<>(FrozenTargetType.class);
			for (FrozenContent row : active) {
				if (row.getTargetType() == null || row.getTargetId() == null) {
					continue;
				}
				ids.computeIfAbsent(row.getTargetType(), key -> new HashSet<>()).add(row.getTargetId());
			}
			snapshot = new Snapshot(true, Map.copyOf(ids));
			if (!active.isEmpty()) {
				log.warn("Frozen-content registry loaded: {} active freeze(s)", active.size());
			}
		}
		catch (RuntimeException ex) {
			// Fail closed. An unreachable registry is indistinguishable from an empty
			// one only if you are willing to guess, and the cost of guessing wrong
			// here is serving material a freeze exists to withhold.
			log.error("Could not load the frozen-content registry — content reads will fail closed: {}",
					ex.toString());
			snapshot = Snapshot.unknown();
		}
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
	 * @param objectKeys every stored object the target owns. Each becomes its own
	 *                   {@link FrozenTargetType#OBJECT} row, because the byte guard
	 *                   is a set lookup on the key and cannot walk back to an entity
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
		FrozenContent row;
		// The refresh is in a finally because a half-written freeze is the worst of
		// the three outcomes: the target row is already persisted, so the database
		// says frozen, while the in-memory snapshot every read path consults still
		// says readable — and the caller swallows the failure. Refreshing whatever
		// did get written keeps the two from disagreeing until the next restart.
		try {
			row = repository.findByTargetTypeAndTargetId(
							request.targetType(), request.targetId())
					.filter(FrozenContent::active)
					.orElseGet(() -> upsert(request, now));
			for (String objectKey : keys(request.objectKeys())) {
				repository.findByTargetTypeAndTargetId(FrozenTargetType.OBJECT, objectKey)
						.filter(FrozenContent::active)
						.orElseGet(() -> upsert(new Request(FrozenTargetType.OBJECT, objectKey,
								request.targetId(), List.of(), request.category(), request.reportId(),
								request.reporterId(), request.actor(), request.reason()), now));
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
				.log();
		return row;
	}

	/**
	 * Writes or revives one row.
	 *
	 * <p>Reviving a released row rather than inserting beside it: the unique index
	 * on (type, id) is what makes concurrent freezes idempotent, and a second
	 * document for the same target would violate it. The previous release stays
	 * readable in the audit log, which is where the history belongs.
	 */
	private FrozenContent upsert(Request request, Instant now) {
		FrozenContent row = repository
				.findByTargetTypeAndTargetId(request.targetType(), request.targetId())
				.orElseGet(() -> FrozenContent.builder()
						.targetType(request.targetType())
						.targetId(request.targetId())
						.build());
		row.setContextId(request.contextId());
		row.setObjectKeys(keys(request.objectKeys()));
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
