package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.timetracking.MoneyBudgets;
import com.ahmadre.hinata.timetracking.ProjectTimeSettings;
import com.ahmadre.hinata.timetracking.TimeEntryWrites;
import com.ahmadre.hinata.timetracking.TimeRounding;
import com.ahmadre.hinata.timetracking.WorkItem;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Billing's answer to what a project's money budget has used (HIN-155): its entries valued the way
 * a billing report values them, each on its own day at the rate in force then, after the
 * instance's rounding.
 *
 * <p>Held for 30 seconds per project and kind, forgotten at once when an entry of the project is
 * written and entirely when a rate changes, so a figure is never older than that.
 */
@Component
@RequiredArgsConstructor
class BillingMoneyBudgets implements MoneyBudgets {

	static final Duration TTL = Duration.ofSeconds(30);
	static final int MAX_HELD = 10_000;

	private final BillingSettings settings;
	private final BillingNames names;
	private final MongoTemplate mongo;
	private final Clock clock;

	private record Key(String projectId, ProjectTimeSettings.RateKind kind) {
	}

	private record Held(long cents, Instant until) {
	}

	private final Map<Key, Held> held = new ConcurrentHashMap<>();

	@Override
	public boolean enabled() {
		return settings.enabled();
	}

	@Override
	public String currency() {
		return settings.currency();
	}

	/** Counts every forget; see {@link com.ahmadre.hinata.timetracking.TimeUsageCache}. */
	private final java.util.concurrent.atomic.AtomicLong forgets = new java.util.concurrent.atomic.AtomicLong();

	@Override
	public Map<String, Long> usedCents(Map<String, ProjectTimeSettings.RateKind> projects) {
		Instant now = clock.instant();
		Map<String, Long> found = new HashMap<>();
		Map<ProjectTimeSettings.RateKind, List<String>> missing = new EnumMap<>(ProjectTimeSettings.RateKind.class);
		projects.forEach((projectId, kind) -> {
			Held cached = held.get(new Key(projectId, kind));
			if (cached != null && cached.until().isAfter(now)) {
				found.put(projectId, cached.cents());
			}
			else {
				missing.computeIfAbsent(kind, k -> new ArrayList<>()).add(projectId);
			}
		});
		if (missing.isEmpty()) {
			return found;
		}
		long before = forgets.get();
		// The directory once for every project asked, the rates once per kind.
		Map<String, List<String>> teams = names.teamsByPerson();
		Map<Key, Long> valued = new HashMap<>();
		missing.forEach((kind, ids) -> value(ids, kind, teams)
				.forEach((projectId, cents) -> valued.put(new Key(projectId, kind), cents)));
		boolean current = forgets.get() == before;
		if (held.size() + valued.size() > MAX_HELD) {
			held.clear();
		}
		valued.forEach((key, cents) -> {
			if (current) {
				held.put(key, new Held(cents, now.plus(TTL)));
			}
			found.put(key.projectId(), cents);
		});
		return found;
	}

	/** An entry of these projects moved: their figures are stale. */
	@EventListener
	public void onWrite(TimeEntryWrites.Touched touched) {
		forgets.incrementAndGet();
		held.keySet().removeIf(key -> touched.projectIds().contains(key.projectId()));
	}

	/** Entries went whose projects are not known: every figure may have moved. */
	@EventListener
	public void onUnknownWrite(TimeEntryWrites.TouchedUnknown touched) {
		forgetAll();
	}

	/** A rate changed: every figure may have. */
	void forgetAll() {
		forgets.incrementAndGet();
		held.clear();
	}

	/**
	 * The cents of [projectIds] at the rates of [kind]. One aggregation for all of them, grouped by
	 * project, person, issue, day and duration: a rate depends on the first four, and rounding
	 * applies to each entry's own duration, so entries alike in all five are valued once and
	 * counted. Far fewer rows than entries, and none of an entry's other fields.
	 */
	private Map<String, Long> value(List<String> projectIds, ProjectTimeSettings.RateKind kind,
			Map<String, List<String>> teams) {
		boolean revenue = kind == ProjectTimeSettings.RateKind.BILLABLE;
		RateResolver resolver = RateResolver.of(mongo.find(Query.query(Criteria.where("kind")
				.is(revenue ? BillingRate.Kind.BILLABLE : BillingRate.Kind.COST)), BillingRate.class));
		Criteria criteria = Criteria.where("projectId").in(projectIds);
		if (revenue) {
			criteria = criteria.and("billable").is(true);
		}
		Map<String, Long> centMinutes = new HashMap<>();
		projectIds.forEach(id -> centMinutes.put(id, 0L));
		mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
						Aggregation.group("projectId", "userId", "issueId", "date", "durationMinutes").count().as("entries"))
						.withOptions(AggregationOptions.builder().allowDiskUse(true).build()),
				WorkItem.class, Document.class).forEach(row -> {
					Document id = row.get("_id", Document.class);
					String projectId = id.getString("projectId");
					String userId = id.getString("userId");
					LocalDate day = BillingReportService.day(id.get("date"));
					int minutes = TimeRounding.round(id.get("durationMinutes") instanceof Number number
							? number.intValue() : 0, settings.rounding());
					BillingRate rate = resolver.resolve(new RateResolver.Facts(userId, projectId, id.getString("issueId"),
							day, userId == null ? List.of() : teams.getOrDefault(userId, List.of())));
					if (rate != null) {
						long entries = ((Number) row.get("entries")).longValue();
						centMinutes.merge(projectId, Money.centMinutes(rate.getAmountCents(), minutes) * entries, Long::sum);
					}
				});
		Map<String, Long> cents = new HashMap<>();
		centMinutes.forEach((projectId, total) -> cents.put(projectId, Money.cents(total)));
		return cents;
	}
}
