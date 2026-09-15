package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserController.DirectoryUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mongodb.MongoExecutionTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Reads a board's cards page by page, for the wall, a sprint, the backlog and the timeline, and what
 * the board's filter and its timeline need besides.
 *
 * <p>It replaces a board that loaded every issue of its projects at full weight and searched them on
 * the client. Every read here is one viewer's: the board's projects that viewer may see, active issues
 * only, narrowed on the server by the board's search and filter ({@link BoardCriteria}), in pages of at
 * most {@value #MAX_PAGE_SIZE} cards.
 *
 * <p>Each read names the index that serves it instead of leaving the choice to the planner, which
 * chooses differently as a board grows. A column's page and its count come off {@code board_column},
 * a sprint's and the backlog's off {@code board_sprint}, both in board order ({@code rank}, then
 * {@code _id}) without a sort; the timeline comes off {@code board_timeline} in date order. The
 * sub-tasks of a sprint, which come in by two ways, are the one read the planner plans itself. A page
 * reads only the state spellings its column was counted in, and no read runs longer than
 * {@link #MAX_TIME}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardReader {

	static final int MAX_PAGE_SIZE = 100;

	/** How far into a query a page may start. Nobody scrolls a column past ten thousand cards. */
	static final int MAX_OFFSET = 10_000;

	/** How long a read may take before the server gives up on it and says it is busy. */
	static final Duration MAX_TIME = Duration.ofSeconds(5);

	/** How long a board's facets are served from memory before they are gathered again. */
	static final Duration FACETS_FRESH = Duration.ofSeconds(30);

	/** The most cards a view may ask the connectors between at once. */
	static final int MAX_LINK_CARDS = 1_000;

	private static final int MAX_CACHED_FACETS = 256;
	private static final int MAX_FACET_PEOPLE = 200;
	private static final int MAX_FACET_VALUES = 500;
	private static final int MAX_EPICS = 200;

	private static final String ISSUES = "issues";
	private static final String EPIC = Issue.Type.EPIC.name();
	private static final Sort BOARD_ORDER = Sort.by(Sort.Order.asc("rank"), Sort.Order.asc("_id"));
	private static final Sort TIMELINE_ORDER =
			Sort.by(Sort.Order.asc("startDate"), Sort.Order.asc("dueDate"), Sort.Order.asc("_id"));

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final BoardAccess access;
	private final BoardCardAssembler assembler;
	private final IssueLinkGraphService graph;
	private final MongoTemplate mongo;
	private final Clock clock;

	private final Map<FacetKey, CachedFacets> facetCache = new ConcurrentHashMap<>();

	/** The indexes a read found missing, each warned about once. */
	private final Set<String> missingIndexes = ConcurrentHashMap.newKeySet();

	/** A column of the wall: how many cards it holds and the first page of them. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WallColumn(String name, List<String> states, Integer wipLimit, int hue, Long total,
			List<BoardCard> issues) {
	}

	/**
	 * A board as its wall first paints it.
	 *
	 * @param sprintId the sprint the wall shows: the one asked for, else the board's active one
	 * @param refs     the epics and parents the cards name
	 */
	public record BoardWall(AgileBoard board, List<Sprint> sprints, String sprintId, List<WallColumn> columns,
			List<DirectoryUser> users, List<BoardRef> refs) {
	}

	/**
	 * Where a page of cards comes from: the column of that name, the sprint, the backlog, or with
	 * [dated] the timeline's cards with a date or those without. A column may lie in a sprint.
	 */
	public record CardSource(String column, String sprintId, boolean backlog, Boolean dated) {

		/** Whether the source names where its cards come from. */
		boolean named() {
			return present(column) || present(sprintId) || backlog || dated != null;
		}
	}

	/** The cards of one workflow state: how many, whether resolved, and their story points. */
	public record StateSummary(String state, boolean resolved, long count, long points) {
	}

	/** One page of cards, with the people and references on it, and on request a summary by state. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BoardCardPage(List<BoardCard> content, long totalElements, int page, int size,
			List<DirectoryUser> users, List<BoardRef> refs, List<StateSummary> summary) {
	}

	/** What a board's filter and its row of faces can offer, over every card of the board. */
	public record BoardFacets(List<String> assigneeIds, List<String> reporterIds, List<String> labels,
			List<String> states, List<String> types, List<String> priorities, List<BoardRef> epics,
			List<DirectoryUser> users) {
	}

	/**
	 * The wall of a board: its columns, each with its number of cards and the first [size] of them. A
	 * [size] of 0 returns the columns alone, without counting anything, and so does a Scrum board
	 * between two sprints, which shows no wall.
	 */
	public BoardWall wall(String boardId, String sprintId, BoardQuery query, int size, User user) {
		return timed(() -> readWall(boardId, sprintId, query, size, user));
	}

	private BoardWall readWall(String boardId, String sprintId, BoardQuery query, int size, User user) {
		int pageSize = pageSize(size);
		checkId(sprintId);
		BoardScope scope = scope(boardId, user);
		String sprint = present(sprintId) ? sprintId : scope.board().getActiveSprintId();
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(boardId);
		boolean betweenSprints = sprint == null && scope.board().getType() == AgileBoard.Type.SCRUM;
		if (pageSize == 0 || betweenSprints) {
			List<WallColumn> bare = scope.columns().stream()
					.map(column -> column(scope, column, null, List.of()))
					.toList();
			return new BoardWall(scope.board(), boardSprints, sprint, bare, List.of(), List.of());
		}
		BoardCriteria.Place place = sprint == null ? BoardCriteria.Place.BOARD : BoardCriteria.Place.sprint(sprint);
		Optional<Criteria> criteria = BoardCriteria.of(scope, place, query, this::idsOf);
		String index = BoardCriteria.index(place, query, null);
		Map<String, Long> byState = criteria.map(found -> countByState(found, index)).orElse(Map.of());
		List<List<Issue>> pages = new ArrayList<>();
		List<Long> totals = new ArrayList<>();
		List<Issue> loaded = new ArrayList<>();
		for (AgileBoard.Column column : scope.columns()) {
			Map<String, Long> counted = counted(byState, column);
			List<Issue> page = counted.isEmpty() ? List.of()
					: find(BoardCriteria.and(criteria.orElseThrow(), BoardCriteria.stateIn(counted.keySet())),
							BOARD_ORDER, 0, pageSize, index);
			pages.add(page);
			totals.add(sum(counted));
			loaded.addAll(page);
		}
		BoardCardAssembler.Cards cards = assembler.cards(scope, loaded);
		List<WallColumn> columns = new ArrayList<>();
		for (int i = 0; i < scope.columns().size(); i++) {
			columns.add(column(scope, scope.columns().get(i), totals.get(i), cards.of(pages.get(i))));
		}
		return new BoardWall(scope.board(), boardSprints, sprint, columns, cards.users(), cards.refs());
	}

	/**
	 * One page of cards of [source], narrowed by [query]. With [summary] the page carries the cards of
	 * the whole sprint by state; only a sprint has one to show.
	 */
	public BoardCardPage cards(String boardId, CardSource source, BoardQuery query, int page, int size,
			boolean summary, User user) {
		return timed(() -> readCards(boardId, source, query, page, size, summary, user));
	}

	private BoardCardPage readCards(String boardId, CardSource source, BoardQuery query, int page, int size,
			boolean summary, User user) {
		int pageSize = pageSize(size);
		checkId(source.sprintId());
		checkId(source.column());
		boolean ofSprint = present(source.sprintId());
		// Every read names where its cards come from: the whole board in board order has no index to
		// come off, and no view reads it.
		if (page < 0 || source.backlog() && ofSprint || !source.named() || summary && !ofSprint) {
			throw invalid();
		}
		BoardScope scope = scope(boardId, user);
		AgileBoard.Column column = null;
		if (present(source.column())) {
			column = scope.column(source.column());
			if (column == null) {
				throw ApiException.badRequest("error.board.unknownColumn", source.column());
			}
		}
		BoardCriteria.Place place = source.backlog() ? BoardCriteria.Place.BACKLOG
				: ofSprint ? BoardCriteria.Place.sprint(source.sprintId()) : BoardCriteria.Place.BOARD;
		Optional<Criteria> found = BoardCriteria.of(scope, place, query, this::idsOf);
		if (found.isEmpty()) {
			return new BoardCardPage(List.of(), 0, page, pageSize, List.of(), List.of(),
					summary ? List.of() : null);
		}
		String index = BoardCriteria.index(place, query, source.dated());
		Criteria criteria = found.get();
		if (source.dated() != null) {
			criteria = BoardCriteria.and(criteria, source.dated() ? BoardCriteria.dated() : BoardCriteria.undated());
		}
		Criteria scoped = criteria;
		long total;
		if (column != null) {
			Map<String, Long> counted = counted(countByState(BoardCriteria.and(criteria,
					BoardCriteria.stateIn(BoardScope.spellings(column.getStates()))), index), column);
			total = sum(counted);
			criteria = BoardCriteria.and(criteria, BoardCriteria.stateIn(counted.keySet()));
		}
		else {
			total = count(criteria, index);
		}
		long offset = (long) page * pageSize;
		Sort order = source.dated() != null ? TIMELINE_ORDER : BOARD_ORDER;
		List<Issue> content = readsPage(offset, total, pageSize)
				? find(criteria, order, offset, pageSize, index)
				: List.of();
		BoardCardAssembler.Cards cards = assembler.cards(scope, content);
		return new BoardCardPage(cards.of(content), total, page, pageSize, cards.users(), cards.refs(),
				summary ? summarize(scoped, index) : null);
	}

	/**
	 * What the filter can offer over every card of [shape] on the board, in the sprint or in the
	 * backlog: a planning offers the epics and sub-tasks it lists, a wall only its work items. The
	 * answer is kept for {@link #FACETS_FRESH} for everyone who reads the same projects there, so a
	 * board opened again and again gathers it once.
	 */
	public BoardFacets facets(String boardId, String sprintId, boolean backlog, BoardQuery.Shape shape, User user) {
		return timed(() -> readFacets(boardId, sprintId, backlog, shape, user));
	}

	private BoardFacets readFacets(String boardId, String sprintId, boolean backlog, BoardQuery.Shape shape,
			User user) {
		checkId(sprintId);
		if (backlog && present(sprintId)) {
			throw invalid();
		}
		BoardScope scope = scope(boardId, user);
		BoardCriteria.Place place = backlog ? BoardCriteria.Place.BACKLOG
				: present(sprintId) ? BoardCriteria.Place.sprint(sprintId) : BoardCriteria.Place.BOARD;
		FacetKey key = new FacetKey(scope.projectIds(), place, shape);
		Instant now = clock.instant();
		CachedFacets cached = facetCache.get(key);
		if (cached != null && now.isBefore(cached.until())) {
			return cached.facets();
		}
		BoardFacets facets = gatherFacets(scope, place, shape);
		if (facetCache.size() >= MAX_CACHED_FACETS) {
			facetCache.values().removeIf(entry -> !now.isBefore(entry.until()));
			if (facetCache.size() >= MAX_CACHED_FACETS) {
				facetCache.clear();
			}
		}
		facetCache.put(key, new CachedFacets(now.plus(FACETS_FRESH), facets));
		return facets;
	}

	/**
	 * The connectors between the cards [ids] of the board that a view holds, both ends among them:
	 * what a timeline draws. A card of a project the viewer may not see is no end of any.
	 */
	public List<IssueLinkGraphService.LinkEdge> links(String boardId, List<String> ids, User user) {
		return timed(() -> readLinks(boardId, ids, user));
	}

	private List<IssueLinkGraphService.LinkEdge> readLinks(String boardId, List<String> ids, User user) {
		if (ids == null || ids.size() > MAX_LINK_CARDS) {
			throw invalid();
		}
		ids.forEach(BoardReader::checkId);
		BoardScope scope = scope(boardId, user);
		List<String> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
		if (wanted.isEmpty()) {
			return List.of();
		}
		Query query = Query.query(Criteria.where("_id").in(wanted).and("projectId").in(scope.projectIds())
				.and("archived").is(false)).maxTime(MAX_TIME);
		query.fields().include("dependsOnIds").include(Issue.PROJECTION_REQUIRED);
		return graph.among(mongo.find(query, Issue.class));
	}

	/**
	 * The board as [user] may read it: the projects of it the viewer may see, active ones only, in the
	 * board's order, and the columns built from them.
	 *
	 * @throws ApiException 404 for a board that does not exist, 403 when the viewer may see none of its
	 *                      projects. An admin reads such a board as empty.
	 */
	BoardScope scope(String boardId, User user) {
		AgileBoard board = boards.findById(boardId).orElseThrow(() -> ApiException.notFound("board"));
		List<Project> spanned = access.assertReadable(board, user);
		List<AgileBoard.Column> columns = board.hasCustomColumns()
				? BoardColumns.reconcile(board.getColumns(), spanned)
				: BoardColumns.merge(spanned);
		return new BoardScope(board, spanned, columns, BoardColumns.hues(columns, spanned));
	}

	/**
	 * Runs [read] and says the server is busy when the database gave up on it after {@link #MAX_TIME}: a
	 * read that took too long ends in a 503 the app can explain rather than in a 500.
	 */
	private static <T> T timed(Supplier<T> read) {
		try {
			return read.get();
		}
		catch (RuntimeException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof MongoExecutionTimeoutException) {
					throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "error.board.busy");
				}
			}
			throw ex;
		}
	}

	/** Whether a page from [offset] of [total] cards holds any, within the reach of a page. */
	static boolean readsPage(long offset, long total, int pageSize) {
		return pageSize > 0 && offset < total && offset <= MAX_OFFSET;
	}

	private BoardFacets gatherFacets(BoardScope scope, BoardCriteria.Place place, BoardQuery.Shape shape) {
		Criteria criteria = BoardCriteria.of(scope, place, BoardQuery.all(shape), this::idsOf).orElseThrow();
		Document facets = mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria), FACET_FIELDS, FACETS)
				.withOptions(options(null)), ISSUES, Document.class).getUniqueMappedResult();
		List<String> assignees = values(facets, "assignees");
		List<String> reporters = values(facets, "reporters");

		// The oldest epics first, so the ceiling keeps the same ones on every read.
		Query epicQuery = Query.query(Criteria.where("projectId").in(scope.projectIds()).and("archived").is(false)
				.and("type").is(EPIC)).with(Sort.by("numberInProject", "_id")).limit(MAX_EPICS).maxTime(MAX_TIME);
		epicQuery.fields().include(BoardRef.FIELDS).include(Issue.PROJECTION_REQUIRED);
		List<String> order = scope.projectIds();
		List<BoardRef> epics = mongo.find(epicQuery, Issue.class).stream()
				.sorted(Comparator.<Issue>comparingInt(epic -> order.indexOf(epic.getProjectId()))
						.thenComparingLong(Issue::getNumberInProject))
				.map(BoardRef::of)
				.toList();

		return new BoardFacets(assignees, reporters, values(facets, "labels"), values(facets, "states"),
				values(facets, "types"), values(facets, "priorities"), epics,
				assembler.people(Stream.concat(assignees.stream(), reporters.stream())));
	}

	private List<Issue> find(Criteria criteria, Sort order, long offset, int limit, String index) {
		return withIndex(index, hint -> {
			Query query = limited(Query.query(criteria).with(order).skip(offset).limit(limit), hint);
			query.fields().include(BoardCard.FIELDS).include(Issue.PROJECTION_REQUIRED);
			return mongo.find(query, Issue.class);
		});
	}

	private long count(Criteria criteria, String index) {
		return withIndex(index, hint -> mongo.count(limited(Query.query(criteria), hint), Issue.class));
	}

	/** The ids of the issues [criteria] matches, read as plain documents. */
	private List<String> idsOf(Criteria criteria) {
		Query query = Query.query(criteria).limit(BoardCriteria.MAX_LINKED).maxTime(MAX_TIME);
		query.fields().include("_id");
		return mongo.find(query, Document.class, ISSUES).stream()
				.map(document -> document.get("_id").toString())
				.toList();
	}

	/** The number of issues per stored state, in one pass over the query. */
	private Map<String, Long> countByState(Criteria criteria, String index) {
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

	private List<StateSummary> summarize(Criteria criteria, String index) {
		List<StateSummary> summary = new ArrayList<>();
		for (Document row : withIndex(index, hint -> mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria),
				SUMMARY).withOptions(options(hint)), ISSUES, Document.class))) {
			Document key = row.get("_id", Document.class);
			summary.add(new StateSummary(key.getString("state"), Boolean.TRUE.equals(key.getBoolean("resolved")),
					((Number) row.get("count")).longValue(), ((Number) row.get("points")).longValue()));
		}
		summary.sort(Comparator.comparing(StateSummary::state, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
				.thenComparing(StateSummary::resolved));
		return summary;
	}

	/**
	 * Runs [read] with the hint [index], and without it when the database has no such index, as a
	 * database restored without its indexes: such a board reads slower, but it reads.
	 */
	private <T> T withIndex(String index, Function<String, T> read) {
		if (index == null) {
			return read.apply(null);
		}
		try {
			return read.apply(index);
		}
		catch (DataAccessException ex) {
			if (!missingIndex(ex)) {
				throw ex;
			}
			if (missingIndexes.add(index)) {
				log.warn("The issues have no index {}; board reads go without it", index);
			}
			return read.apply(null);
		}
	}

	private static boolean missingIndex(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains("does not correspond to an existing index")) {
				return true;
			}
		}
		return false;
	}

	/** The counts of the spellings [column]'s states are stored in: the ones its page reads in. */
	private static Map<String, Long> counted(Map<String, Long> byState, AgileBoard.Column column) {
		Map<String, Long> counted = new LinkedHashMap<>();
		for (String spelling : BoardScope.spellings(column.getStates())) {
			Long count = byState.get(spelling);
			if (count != null && count > 0) {
				counted.put(spelling, count);
			}
		}
		return counted;
	}

	private static long sum(Map<String, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

	private static Query limited(Query query, String index) {
		query.maxTime(MAX_TIME);
		return index == null ? query : query.withHint(index);
	}

	private static AggregationOptions options(String index) {
		AggregationOptions.Builder options = AggregationOptions.builder().maxTime(MAX_TIME);
		return (index == null ? options : options.hint(index)).build();
	}

	private static WallColumn column(BoardScope scope, AgileBoard.Column column, Long total, List<BoardCard> cards) {
		return new WallColumn(column.getName(), column.getStates(), scope.wipLimit(column.getName()),
				scope.hue(column.getName()), total, cards);
	}

	private static List<String> values(Document facets, String name) {
		if (facets == null) {
			return List.of();
		}
		return facets.getList(name, Document.class, List.of()).stream()
				.map(row -> row.get("_id"))
				.filter(Objects::nonNull)
				.map(Object::toString)
				.filter(value -> !value.isBlank())
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}

	private static int pageSize(int size) {
		if (size < 0) {
			throw invalid();
		}
		return Math.min(size, MAX_PAGE_SIZE);
	}

	private static void checkId(String value) {
		if (value != null && value.length() > BoardQuery.MAX_VALUE_LENGTH) {
			throw invalid();
		}
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	private static ApiException invalid() {
		return ApiException.badRequest("error.validationFailed");
	}

	/** The projects, the place and the shape a board's facets were gathered over. */
	private record FacetKey(List<String> projectIds, BoardCriteria.Place place, BoardQuery.Shape shape) {
	}

	private record CachedFacets(Instant until, BoardFacets facets) {
	}

	/** Only the fields the facets read, so the pipeline carries no description or document. */
	private static final AggregationOperation FACET_FIELDS = context -> new Document("$project",
			new Document("assigneeIds", 1).append("assigneeId", 1).append("reporterId", 1).append("tags", 1)
					.append("state", 1).append("type", 1).append("priority", 1));

	/** Every facet in one pass: the distinct people, labels, states, types and priorities. */
	private static final AggregationOperation FACETS = context -> new Document("$facet", new Document()
			.append("assignees", List.of(
					new Document("$project", new Document("person", new Document("$concatArrays", List.of(
							new Document("$ifNull", Arrays.asList("$assigneeIds", List.of())),
							List.of(new Document("$ifNull", Arrays.asList("$assigneeId", null))))))),
					new Document("$unwind", "$person"),
					new Document("$match", new Document("person", new Document("$nin", Arrays.asList(null, "")))),
					new Document("$group", new Document("_id", "$person")),
					new Document("$limit", MAX_FACET_PEOPLE)))
			.append("reporters", List.of(
					new Document("$match", new Document("reporterId", new Document("$nin", Arrays.asList(null, "")))),
					new Document("$group", new Document("_id", "$reporterId")),
					new Document("$limit", MAX_FACET_PEOPLE)))
			.append("labels", List.of(
					new Document("$unwind", "$tags"),
					new Document("$group", new Document("_id", "$tags")),
					new Document("$limit", MAX_FACET_VALUES)))
			.append("states", List.of(
					new Document("$group", new Document("_id", "$state")),
					new Document("$limit", MAX_FACET_VALUES)))
			.append("types", List.of(new Document("$group", new Document("_id", "$type"))))
			.append("priorities", List.of(new Document("$group", new Document("_id", "$priority")))));

	/** Count and story points per state and resolution. */
	private static final AggregationOperation SUMMARY = context -> new Document("$group", new Document("_id",
			new Document("state", "$state").append("resolved", new Document("$gt", Arrays.asList("$resolvedAt", null))))
			.append("count", new Document("$sum", 1))
			.append("points", new Document("$sum", new Document("$ifNull", Arrays.asList("$storyPoints", 0)))));
}
