package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.mongodb.MongoServerException;
import com.mongodb.client.DistinctIterable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The reads of issues behind a board, each off the index it names: pages of cards, counts, counts by
 * state, a sprint's summary, the ids a criteria matches, and the distinct values an index holds.
 *
 * <p>A read names its index instead of leaving the choice to the planner, which chooses differently as
 * a board grows, and none runs longer than {@link BoardTime} allows. A read whose index the database does not
 * have, as a database restored without its indexes or one still building a new index after an
 * upgrade, goes without it: such a board reads slower, but it reads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BoardIssueReads {

	private static final String ISSUES = "issues";

	/** The code MongoDB refuses a hint to an index it does not have with, as it refuses other bad values. */
	private static final int BAD_VALUE = 2;

	private final MongoTemplate mongo;

	/** The key fields of the indexes the reads step through, by index name. */
	private final Map<String, List<String>> indexKeys = new ConcurrentHashMap<>();

	/** The indexes a read found missing or shaped otherwise, each warned about once. */
	private final Set<String> warnedIndexes = ConcurrentHashMap.newKeySet();

	/** Up to [limit] issues [criteria] matches, from [offset] in [order], with [fields] and nothing more. */
	List<Issue> find(Criteria criteria, Sort order, long offset, int limit, String index, String... fields) {
		return withIndex(index, hint -> {
			Query query = limited(Query.query(criteria).with(order).skip(offset).limit(limit), hint);
			query.fields().include(fields).include(Issue.PROJECTION_REQUIRED);
			return mongo.find(query, Issue.class);
		});
	}

	long count(Criteria criteria, String index) {
		return withIndex(index, hint -> mongo.count(limited(Query.query(criteria), hint), Issue.class));
	}

	/** The number of issues per stored state, in one pass over the query. */
	Map<String, Long> countByState(Criteria criteria, String index) {
		Map<String, Long> counts = new HashMap<>();
		for (Document row : withIndex(index, hint -> mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group("state").count().as("count")).withOptions(options(hint)), ISSUES, Document.class))) {
			Object state = row.get("_id");
			if (state != null) {
				counts.merge(state.toString(), ((Number) row.get("count")).longValue(), Long::sum);
			}
		}
		return counts;
	}

	/** Count and story points of the issues [criteria] matches, per state and resolution. */
	List<BoardStateSummary> summarize(Criteria criteria, String index) {
		List<BoardStateSummary> summary = new ArrayList<>();
		for (Document row : withIndex(index, hint -> mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
				SUMMARY).withOptions(options(hint)), ISSUES, Document.class))) {
			Document key = row.get("_id", Document.class);
			summary.add(new BoardStateSummary(key.getString("state"), Boolean.TRUE.equals(key.getBoolean("resolved")),
					((Number) row.get("count")).longValue(), ((Number) row.get("points")).longValue()));
		}
		summary.sort(Comparator.comparing(BoardStateSummary::state, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
				.thenComparing(BoardStateSummary::resolved));
		return summary;
	}

	/** The ids of the issues [criteria] matches, at most {@link BoardCriteria#MAX_LINKED}. */
	List<String> idsOf(Criteria criteria, String index) {
		return withIndex(index, hint -> {
			Query query = limited(Query.query(criteria).limit(BoardCriteria.MAX_LINKED), hint);
			query.fields().include("_id");
			return mongo.find(query, Document.class, ISSUES).stream()
					.map(document -> document.get("_id").toString())
					.toList();
		});
	}

	/**
	 * The steps through the indexes that the reads of values of one request may still take together.
	 * Once they run out, a read of values takes them off the issues instead, in one read bounded by
	 * {@link BoardTime}: a board of many projects that share many people or labels costs that read,
	 * not one short read per value per project, and no request sends more than its steps.
	 */
	static final class Steps {

		private int left;

		Steps(int left) {
			this.left = left;
		}

		/** Takes a step, or says there is none left. */
		boolean take() {
			if (left == 0) {
				return false;
			}
			left--;
			return true;
		}
	}

	/**
	 * What one read over the issues of [projectIds] looks up. The spellings the issues store their
	 * states in are asked for once, the first time the read matches states, and not at all by a read
	 * that does not.
	 */
	BoardCriteria.Lookup lookupFor(List<String> projectIds) {
		return new ReadLookup(projectIds);
	}

	private final class ReadLookup implements BoardCriteria.Lookup {

		private final List<String> projectIds;
		private Set<String> storedStates;

		private ReadLookup(List<String> projectIds) {
			this.projectIds = projectIds;
		}

		@Override
		public List<String> idsOf(Criteria criteria, String index) {
			return BoardIssueReads.this.idsOf(criteria, index);
		}

		@Override
		public Set<String> spellings(Collection<String> states) {
			if (storedStates == null) {
				storedStates = Set.copyOf(distinct("state", projectIds, BoardCriteria.BY_STATE));
			}
			return BoardCriteria.spellings(states, storedStates);
		}
	}

	/** The active issues among [ids] of [projectIds], each with the issues it depends on, off their ids. */
	List<Issue> dependencies(Collection<String> ids, List<String> projectIds) {
		Query query = limited(Query.query(Criteria.where("_id").in(ids).and("projectId").in(projectIds)
				.and("archived").is(false)), BoardCriteria.BY_ID);
		query.fields().include("dependsOnIds").include(Issue.PROJECTION_REQUIRED);
		return mongo.find(query, Issue.class);
	}

	/**
	 * The distinct texts of [field] among the active issues of [projectIds]. Off [index], which starts
	 * with projectId, archived and [field], a field of one value is read by a distinct scan: one key
	 * per value, however many issues hold it.
	 */
	List<String> distinct(String field, List<String> projectIds, String index) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		Document active = active(projectIds);
		return withIndex(index, hint -> mongo.execute(ISSUES, collection -> {
			DistinctIterable<BsonValue> values = collection.distinct(field, active, BsonValue.class)
					.maxTime(BoardTime.left().toMillis(), TimeUnit.MILLISECONDS);
			if (hint != null) {
				values.hintString(hint);
			}
			List<String> texts = new ArrayList<>();
			for (BsonValue value : values) {
				// The issues without the field hand in a null.
				if (value != null && value.isString() && !value.asString().getValue().isBlank()) {
					texts.add(value.asString().getValue());
				}
			}
			return texts;
		}));
	}

	/**
	 * The distinct texts of [field] among the active issues of [projectIds], at most [limit] of them in
	 * text order, for a field that holds a list, which no distinct scan steps through. Off [index],
	 * which starts with projectId, archived and [field], the read steps from one value to the next: one
	 * short read per value, however many issues hold it, where asking the issues would cost as much as
	 * the board is large.
	 */
	List<String> keysOf(String field, List<String> projectIds, String index, int limit, Steps steps) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		List<String> keys = indexKeys(index);
		if (keys == null) {
			warnMissing(index);
			return firstInOrder(distinct(field, projectIds, null), limit);
		}
		if (keys.size() < 3 || !keys.subList(0, 3).equals(List.of("projectId", "archived", field))) {
			if (warnedIndexes.add(index)) {
				log.warn("The index {} does not start with projectId, archived and {}; board reads go without it",
						index, field);
			}
			return firstInOrder(distinct(field, projectIds, null), limit);
		}
		return firstInOrder(withIndex(index, hint -> {
			Collection<String> stepped = hint == null ? null : stepThrough(field, projectIds, hint, keys, limit, steps);
			return stepped != null ? stepped : distinct(field, projectIds, null);
		}), limit);
	}

	/**
	 * The values of [field] in [index], each project's read from the key past the last value to the
	 * next value's first key, until there is none or [limit] are found; null once [steps] run out first.
	 */
	private Collection<String> stepThrough(String field, List<String> projectIds, String index, List<String> keys,
			int limit, Steps steps) {
		Set<String> values = new LinkedHashSet<>();
		return mongo.execute(ISSUES, collection -> {
			for (String projectId : projectIds) {
				String last = "";
				while (values.size() < limit) {
					if (!steps.take()) {
						return null;
					}
					Document next = collection.find()
							.hintString(index)
							.min(bound(keys, projectId, last, new MaxKey()))
							.max(bound(keys, projectId, new Document(), new MinKey()))
							.returnKey(true)
							.limit(1)
							.maxTime(BoardTime.left().toMillis(), TimeUnit.MILLISECONDS)
							.first();
					if (next == null || !(next.get(field) instanceof String value)) {
						break;
					}
					values.add(value);
					last = value;
				}
			}
			return values;
		});
	}

	/**
	 * A bound of [keys] among the active issues of [projectId]: [value] for the third key, and [rest]
	 * for every key after it. From a text with the greatest rest, a read starts past every key of that
	 * text; up to an empty document with the least rest, it ends past the last text.
	 */
	private static Document bound(List<String> keys, String projectId, Object value, Object rest) {
		Document bound = new Document(keys.get(0), projectId).append(keys.get(1), false).append(keys.get(2), value);
		for (String key : keys.subList(3, keys.size())) {
			bound.append(key, rest);
		}
		return bound;
	}

	/**
	 * Runs [read] with the hint [index], and without it when the database has no such index: a read
	 * refused as a bad value, of an index the database does not list.
	 */
	private <T> T withIndex(String index, Function<String, T> read) {
		if (index == null) {
			return read.apply(null);
		}
		try {
			return read.apply(index);
		}
		catch (RuntimeException ex) {
			if (!refusedAsBadValue(ex) || hasIndex(index)) {
				throw ex;
			}
			warnMissing(index);
			return read.apply(null);
		}
	}

	private static boolean refusedAsBadValue(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof MongoServerException server && server.getCode() == BAD_VALUE) {
				return true;
			}
		}
		return false;
	}

	/** Whether the issues have [index], asked of the database again. */
	private boolean hasIndex(String index) {
		indexKeys.remove(index);
		return indexKeys(index) != null;
	}

	/** The key fields of [index] in their order, or null when the issues have no such index. */
	private List<String> indexKeys(String index) {
		List<String> known = indexKeys.get(index);
		if (known != null) {
			return known;
		}
		List<String> keys = mongo.indexOps(ISSUES).getIndexInfo().stream()
				.filter(info -> index.equals(info.getName()))
				.findFirst()
				.map(info -> info.getIndexFields().stream().map(IndexField::getKey).toList())
				.orElse(null);
		if (keys != null) {
			indexKeys.put(index, keys);
		}
		return keys;
	}

	private void warnMissing(String index) {
		if (warnedIndexes.add(index)) {
			log.warn("The issues have no index {}; board reads go without it", index);
		}
	}

	/** The first [limit] of [values] in text order, ignoring case, blanks left out. */
	private static List<String> firstInOrder(Collection<String> values, int limit) {
		return values.stream()
				.filter(value -> !value.isBlank())
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(limit)
				.toList();
	}

	private static Query limited(Query query, String index) {
		query.maxTime(BoardTime.left());
		return index == null ? query : query.withHint(index);
	}

	private static AggregationOptions options(String index) {
		AggregationOptions.Builder options = AggregationOptions.builder().maxTime(BoardTime.left());
		return (index == null ? options : options.hint(index)).build();
	}

	private static Document active(List<String> projectIds) {
		return new Document("projectId", new Document("$in", projectIds)).append("archived", false);
	}

	/** Count and story points per state and resolution. */
	private static final AggregationOperation SUMMARY = context -> new Document("$group", new Document("_id",
			new Document("state", "$state").append("resolved", new Document("$gt", Arrays.asList("$resolvedAt", null))))
			.append("count", new Document("$sum", 1))
			.append("points", new Document("$sum", new Document("$ifNull", Arrays.asList("$storyPoints", 0)))));
}
