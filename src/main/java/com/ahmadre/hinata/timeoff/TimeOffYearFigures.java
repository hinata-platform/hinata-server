package com.ahmadre.hinata.timeoff;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The journal read in bulk for one type: who has history in which leave years, what each of them
 * has per kind, and who was told what and when (HIN-119).
 *
 * <p>For the jobs and pages that look at everybody at once — the yearly run, the notices, the list
 * of people nobody told, the report. Each call is one indexed query for a slice of people rather
 * than one per person: a run over two thousand employees that asked per person would be tens of
 * thousands of round trips at three in the morning, and the shape that quietly stops finishing.
 */
@Component
@RequiredArgsConstructor
class TimeOffYearFigures {

	private final MongoTemplate mongo;
	private final TimeOffNoticeRepository notices;

	/** One person's journal for one type and leave year, by kind. */
	record Year(Map<TimeOffLedgerEntry.Kind, Integer> byKind) {

		static final Year EMPTY = new Year(Map.of());

		int of(TimeOffLedgerEntry.Kind kind) {
			return byKind.getOrDefault(kind, 0);
		}

		/** What is left: the sum of every movement. */
		int remaining() {
			int total = 0;
			for (Integer value : byKind.values()) {
				total += value == null ? 0 : value;
			}
			return total;
		}

		/** Days carried in and not yet taken: those go first. */
		int carriedUnused() {
			return Math.max(0, Math.min(of(TimeOffLedgerEntry.Kind.CARRYOVER_IN), remaining()));
		}

		Year plus(TimeOffLedgerEntry.Kind kind, int milliDays) {
			Map<TimeOffLedgerEntry.Kind, Integer> next = new EnumMap<>(TimeOffLedgerEntry.Kind.class);
			next.putAll(byKind);
			next.merge(kind, milliDays, Integer::sum);
			return new Year(next);
		}
	}

	/** Everybody with a booking of [typeId] in any of [years], sorted, so slices are stable. */
	List<String> peopleWith(String typeId, Collection<Integer> years) {
		TreeSet<String> ids = new TreeSet<>();
		for (Object id : mongo.findDistinct(Query.query(Criteria.where("typeId").is(typeId).and("year").in(years)),
				"userId", TimeOffLedgerEntry.class, Object.class)) {
			if (id != null) {
				ids.add(id.toString());
			}
		}
		return List.copyOf(ids);
	}

	/** Each of [userIds]' journal for [typeId], per leave year and kind, in one aggregation. */
	Map<String, Map<Integer, Year>> sums(String typeId, Collection<Integer> years, Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<Integer, Map<TimeOffLedgerEntry.Kind, Integer>>> raw = new HashMap<>();
		for (Document row : mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").in(userIds).and("typeId").is(typeId)
								.and("year").in(years)),
						Aggregation.group("userId", "year", "kind").sum("milliDays").as("total")),
				TimeOffLedgerEntry.class, Document.class)) {
			Document id = row.get("_id", Document.class);
			String userId = id.getString("userId");
			Integer year = id.getInteger("year");
			String kind = id.getString("kind");
			if (userId == null || year == null || kind == null) {
				continue;
			}
			raw.computeIfAbsent(userId, key -> new HashMap<>())
					.computeIfAbsent(year, key -> new EnumMap<>(TimeOffLedgerEntry.Kind.class))
					.merge(TimeOffLedgerEntry.Kind.valueOf(kind), toInt(row.get("total")), Integer::sum);
		}
		Map<String, Map<Integer, Year>> sums = new HashMap<>();
		raw.forEach((userId, byYear) -> {
			Map<Integer, Year> perYear = new HashMap<>();
			byYear.forEach((year, byKind) -> perYear.put(year, new Year(byKind)));
			sums.put(userId, perYear);
		});
		return sums;
	}

	/** One person's year out of {@link #sums}, empty when they have none. */
	static Year yearOf(Map<String, Map<Integer, Year>> sums, String userId, int year) {
		return sums.getOrDefault(userId, Map.of()).getOrDefault(year, Year.EMPTY);
	}

	/** The origin year each of [userIds]' carryover into [year] names, where it names one. */
	Map<String, Integer> originYears(String typeId, int year, Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("userId").in(userIds).and("typeId").is(typeId).and("year").is(year)
				.and("kind").is(TimeOffLedgerEntry.Kind.CARRYOVER_IN.name()).and("originYear").ne(null));
		query.fields().include("userId").include("originYear");
		Map<String, Integer> origins = new HashMap<>();
		for (TimeOffLedgerEntry entry : mongo.find(query, TimeOffLedgerEntry.class)) {
			origins.merge(entry.getUserId(), entry.getOriginYear(), Math::min);
		}
		return origins;
	}

	/**
	 * The first notice each of [userIds] got about [year]'s leave of [typeId], as an instant.
	 *
	 * <p>The first rather than the last, because the question is always "had they been told by
	 * then?" — and the earliest notice answers it for every day after it.
	 */
	Map<String, Instant> firstNotices(String typeId, int year, Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Instant> first = new LinkedHashMap<>();
		for (TimeOffNotice notice : notices.findByTypeIdAndYearAndUserIdIn(typeId, year, userIds)) {
			if (notice.getSentAt() != null) {
				first.merge(notice.getUserId(), notice.getSentAt(), (a, b) -> a.isBefore(b) ? a : b);
			}
		}
		return first;
	}

	/** Whether a notice sent at [sentAt] came before [day] began, counted in UTC like every date here. */
	static boolean toldBefore(Instant sentAt, LocalDate day) {
		return sentAt != null && sentAt.isBefore(day.atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	private static int toInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}
}
