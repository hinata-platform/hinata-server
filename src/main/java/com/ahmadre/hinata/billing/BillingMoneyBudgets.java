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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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

	@Override
	public long usedCents(String projectId, ProjectTimeSettings.RateKind kind) {
		Key key = new Key(projectId, kind);
		Instant now = clock.instant();
		Held cached = held.get(key);
		if (cached != null && cached.until().isAfter(now)) {
			return cached.cents();
		}
		long cents = value(projectId, kind);
		if (held.size() >= MAX_HELD) {
			held.clear();
		}
		held.put(key, new Held(cents, now.plus(TTL)));
		return cents;
	}

	/** An entry of these projects moved: their figures are stale. */
	@EventListener
	public void onWrite(TimeEntryWrites.Touched touched) {
		held.keySet().removeIf(key -> touched.projectIds().contains(key.projectId()));
	}

	/** A rate changed: every figure may have. */
	void forgetAll() {
		held.clear();
	}

	private long value(String projectId, ProjectTimeSettings.RateKind kind) {
		boolean revenue = kind == ProjectTimeSettings.RateKind.BILLABLE;
		RateResolver resolver = RateResolver.of(mongo.find(Query.query(Criteria.where("kind")
				.is(revenue ? BillingRate.Kind.BILLABLE : BillingRate.Kind.COST)), BillingRate.class));
		Map<String, List<String>> teams = names.teamsByPerson();
		Criteria criteria = Criteria.where("projectId").is(projectId);
		if (revenue) {
			criteria = criteria.and("billable").is(true);
		}
		Query read = Query.query(criteria).cursorBatchSize(2_000);
		read.fields().include("userId", "issueId", "date", "durationMinutes");
		long centMinutes = 0;
		try (Stream<Document> entries = mongo.query(WorkItem.class).as(Document.class).matching(read).stream()) {
			for (Document entry : (Iterable<Document>) entries::iterator) {
				String userId = entry.getString("userId");
				LocalDate day = BillingReportService.day(entry.get("date"));
				int minutes = TimeRounding.round(entry.get("durationMinutes") instanceof Number number
						? number.intValue() : 0, settings.rounding());
				BillingRate rate = resolver.resolve(new RateResolver.Facts(userId, projectId,
						entry.getString("issueId"), day, userId == null ? List.of() : teams.getOrDefault(userId, List.of())));
				if (rate != null) {
					centMinutes += Money.centMinutes(rate.getAmountCents(), minutes);
				}
			}
		}
		return Money.cents(centMinutes);
	}
}
