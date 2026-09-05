package com.ahmadre.hinata.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ahmadre.hinata.user.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only admin surface over the security audit log: a filtered, paginated
 * feed plus the catalogue of event types that drives the per-event toggles in
 * the admin settings. Writes happen only through {@link AuditService} at the
 * source of each action. Admin-gated by the {@code /api/v1/admin/**} rule.
 */
@Tag(name = "Admin · Audit")
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditController {

	private final MongoTemplate mongo;
	private final UserRepository users;

	// --- DTOs ----------------------------------------------------------------

	/**
	 * {@code actorLabel} / {@code targetLabel} are snapshots taken when the event
	 * was written — they must keep saying who acted even after that account is
	 * renamed or deleted. Pronouns are deliberately the opposite: they are looked
	 * up live ({@code pronounsById}) so a reader never sees a stale set that
	 * misgenders someone who has since changed them. An id with no user behind it
	 * any more simply resolves to null.
	 */
	public record AuditEntryResponse(String id, Instant timestamp, String action, String category,
			String severity, String outcome, String actorId, String actorLabel, String actorPronouns,
			String targetId, String targetLabel, String targetPronouns, String ip, String userAgent,
			java.util.Map<String, String> metadata) {

		static AuditEntryResponse from(AuditLog l, java.util.Map<String, String> pronounsById) {
			return new AuditEntryResponse(l.getId(), l.getTimestamp(),
					l.getAction() == null ? null : l.getAction().name(),
					l.getCategory() == null ? null : l.getCategory().name(),
					l.getSeverity() == null ? null : l.getSeverity().name(),
					l.getOutcome() == null ? null : l.getOutcome().name(),
					l.getActorId(), l.getActorLabel(), pronounsById.get(l.getActorId()),
					l.getTargetId(), l.getTargetLabel(), pronounsById.get(l.getTargetId()),
					l.getIp(), l.getUserAgent(), l.getMetadata());
		}
	}

	public record AuditPageResponse(List<AuditEntryResponse> items, long total, int page,
			int perPage) {
	}

	public record EventTypeResponse(String action, String category, String severity,
			boolean defaultEnabled) {
	}

	// --- Read ----------------------------------------------------------------

	@Operation(summary = "Paginated, filtered security audit log")
	@GetMapping
	public AuditPageResponse list(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String severity,
			@RequestParam(required = false) String outcome,
			@RequestParam(required = false) String actorId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "30") int perPage) {

		List<Criteria> and = new ArrayList<>();
		addEnum(and, "category", category, AuditCategory.class);
		addEnum(and, "action", action, AuditAction.class);
		addEnum(and, "severity", severity, AuditSeverity.class);
		addEnum(and, "outcome", outcome, AuditLog.Outcome.class);
		if (notBlank(actorId)) {
			and.add(Criteria.where("actorId").is(actorId.trim()));
		}
		if (from != null || to != null) {
			Criteria ts = Criteria.where("timestamp");
			if (from != null) ts = ts.gte(from);
			if (to != null) ts = ts.lte(to);
			and.add(ts);
		}
		if (notBlank(query)) {
			String regex = Pattern.quote(query.trim());
			and.add(new Criteria().orOperator(
					Criteria.where("actorLabel").regex(regex, "i"),
					Criteria.where("targetLabel").regex(regex, "i"),
					Criteria.where("ip").regex(regex, "i")));
		}

		Criteria criteria = and.isEmpty() ? new Criteria()
				: new Criteria().andOperator(and.toArray(Criteria[]::new));

		long total = mongo.count(Query.query(criteria), AuditLog.class);
		int pp = Math.min(Math.max(1, perPage), 200);
		int pages = Math.max(1, (int) Math.ceil((double) total / pp));
		int current = Math.min(Math.max(1, page), pages);

		Query q = Query.query(criteria)
				.with(Sort.by(Sort.Direction.DESC, "timestamp"))
				.skip((long) (current - 1) * pp)
				.limit(pp);
		List<AuditLog> rows = mongo.find(q, AuditLog.class);
		java.util.Map<String, String> pronouns = pronounsFor(rows);
		List<AuditEntryResponse> items = rows.stream()
				.map(l -> AuditEntryResponse.from(l, pronouns)).toList();
		return new AuditPageResponse(items, total, current, pp);
	}

	/**
	 * Current pronouns for every account named on this page, actors and targets
	 * alike — one batched lookup for the whole page rather than a read per row,
	 * and only for the rows actually being returned.
	 */
	private java.util.Map<String, String> pronounsFor(List<AuditLog> rows) {
		Set<String> ids = rows.stream()
				.flatMap(l -> Stream.of(l.getActorId(), l.getTargetId()))
				.filter(AuditController::notBlank)
				.collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<String, String> out = new java.util.HashMap<>();
		users.findPronounsByIdIn(ids).forEach(u -> {
			if (notBlank(u.getPronouns())) {
				out.put(u.getId(), u.getPronouns());
			}
		});
		return out;
	}

	@Operation(summary = "Catalogue of audit event types (for the per-event toggles)")
	@GetMapping("/event-types")
	public List<EventTypeResponse> eventTypes() {
		return Arrays.stream(AuditAction.values())
				.map(a -> new EventTypeResponse(a.name(), a.category().name(),
						a.defaultSeverity().name(), a.defaultEnabled()))
				.toList();
	}

	// --- helpers -------------------------------------------------------------

	private static <E extends Enum<E>> void addEnum(List<Criteria> and, String field, String value,
			Class<E> type) {
		if (!notBlank(value)) {
			return;
		}
		try {
			and.add(Criteria.where(field).is(Enum.valueOf(type, value.trim().toUpperCase())));
		}
		catch (IllegalArgumentException ignored) {
			// Unknown filter value → match nothing rather than ignore the filter.
			and.add(Criteria.where(field).is("__none__"));
		}
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
