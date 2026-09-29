package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The audit log as a filtered, paginated feed, cut to what its reader is there for.
 *
 * <p>Two readers, two cuts (HIN-129). The platform administrator gets the platform's
 * records: sign-ins, accounts, configuration, integrations. Records about working
 * time, timesheets and absences name people and their sick days, and belong to the
 * organisation admins, who get exactly those. Records whose metadata names project
 * content (issue keys, page ids) reach the platform feed without that metadata:
 * that something was deleted, when and by whom stays auditable; what it was stays
 * with the project.
 */
@Component
@RequiredArgsConstructor
public class AuditFeed {

	/** Which records a reader gets. */
	public enum Scope {
		/** Platform records; content records without metadata. */
		PLATFORM,
		/** Platform records and the organisation's, for somebody who holds both roles. */
		PLATFORM_AND_ORGANISATION,
		/** The organisation's records only. */
		ORGANISATION
	}

	/** The query parameters both routes accept. */
	public record Filter(String query, String category, String action, String severity, String outcome,
			String actorId, Instant from, Instant to, int page, int perPage) {
	}

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

		static AuditEntryResponse from(AuditLog l, java.util.Map<String, String> pronounsById,
				boolean withMetadata) {
			return new AuditEntryResponse(l.getId(), l.getTimestamp(),
					l.getAction() == null ? null : l.getAction().name(),
					l.getCategory() == null ? null : l.getCategory().name(),
					l.getSeverity() == null ? null : l.getSeverity().name(),
					l.getOutcome() == null ? null : l.getOutcome().name(),
					l.getActorId(), l.getActorLabel(), pronounsById.get(l.getActorId()),
					l.getTargetId(), l.getTargetLabel(), pronounsById.get(l.getTargetId()),
					l.getIp(), l.getUserAgent(), withMetadata ? l.getMetadata() : java.util.Map.of());
		}
	}

	public record AuditPageResponse(List<AuditEntryResponse> items, long total, int page,
			int perPage) {
	}

	public record EventTypeResponse(String action, String category, String severity,
			boolean defaultEnabled) {
	}

	private static final Set<AuditAction> ORGANISATIONAL = Arrays.stream(AuditAction.values())
			.filter(AuditAction::organisational).collect(Collectors.toUnmodifiableSet());

	private static final Set<AuditAction> ABSENCE = Arrays.stream(AuditAction.values())
			.filter(AuditAction::absence).collect(Collectors.toUnmodifiableSet());

	/** The rest, as a positive list: an {@code $in} walks the action+time index, a {@code $nin} cannot. */
	private static final Set<AuditAction> PLATFORM_ONLY = Arrays.stream(AuditAction.values())
			.filter(action -> !action.organisational()).collect(Collectors.toUnmodifiableSet());

	private final MongoTemplate mongo;
	private final UserRepository users;

	/** As {@link #page(Filter, Scope, boolean)} for a reader who keeps absences. */
	public AuditPageResponse page(Filter f, Scope scope) {
		return page(f, scope, true);
	}

	/**
	 * One page of the feed for [scope]. Without {@code absences} the records about
	 * absences stay out: who reported sick is the absence keepers' to read, and an
	 * operator who names that circle means it for the log too.
	 */
	public AuditPageResponse page(Filter f, Scope scope, boolean absences) {
		List<Criteria> and = new ArrayList<>();
		switch (scope) {
			case PLATFORM -> and.add(Criteria.where("action").in(PLATFORM_ONLY));
			case ORGANISATION -> and.add(Criteria.where("action").in(ORGANISATIONAL));
			case PLATFORM_AND_ORGANISATION -> { }
		}
		if (!absences && scope != Scope.PLATFORM) {
			and.add(Criteria.where("action").nin(ABSENCE));
		}
		addEnum(and, "category", f.category(), AuditCategory.class);
		addEnum(and, "action", f.action(), AuditAction.class);
		addEnum(and, "severity", f.severity(), AuditSeverity.class);
		addEnum(and, "outcome", f.outcome(), AuditLog.Outcome.class);
		if (notBlank(f.actorId())) {
			and.add(Criteria.where("actorId").is(f.actorId().trim()));
		}
		if (f.from() != null || f.to() != null) {
			Criteria ts = Criteria.where("timestamp");
			if (f.from() != null) ts = ts.gte(f.from());
			if (f.to() != null) ts = ts.lte(f.to());
			and.add(ts);
		}
		if (notBlank(f.query())) {
			String regex = Pattern.quote(f.query().trim());
			and.add(new Criteria().orOperator(
					Criteria.where("actorLabel").regex(regex, "i"),
					Criteria.where("targetLabel").regex(regex, "i"),
					Criteria.where("ip").regex(regex, "i")));
		}

		Criteria criteria = and.isEmpty() ? new Criteria()
				: new Criteria().andOperator(and.toArray(Criteria[]::new));

		long total = mongo.count(Query.query(criteria), AuditLog.class);
		int pp = Math.min(Math.max(1, f.perPage()), 200);
		int pages = Math.max(1, (int) Math.ceil((double) total / pp));
		int current = Math.min(Math.max(1, f.page()), pages);

		Query q = Query.query(criteria)
				.with(Sort.by(Sort.Direction.DESC, "timestamp"))
				.skip((long) (current - 1) * pp)
				.limit(pp);
		List<AuditLog> rows = mongo.find(q, AuditLog.class);
		java.util.Map<String, String> pronouns = pronounsFor(rows);
		List<AuditEntryResponse> items = rows.stream()
				// Per record, not per reader: no role opens a project's content, so a
				// content record comes without its details whichever feed it is in.
				.map(l -> AuditEntryResponse.from(l, pronouns, l.getAction() == null || !l.getAction().content()))
				.toList();
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
				.filter(AuditFeed::notBlank)
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
