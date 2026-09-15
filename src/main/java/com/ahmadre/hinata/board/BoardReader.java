package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserController.DirectoryUser;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a board's cards page by page, for the wall, a sprint, the backlog and the timeline, and
 * what the board's filter can offer.
 *
 * <p>It replaces a board that loaded every issue of its projects at full weight and searched them
 * on the client. Every read here is one viewer's: the board's projects that viewer may see, active
 * issues only, narrowed on the server by the board's search and filter, in pages of at most
 * {@value #MAX_PAGE_SIZE} cards. Cards come in board order, {@code rank} and then {@code _id}, which
 * the {@code board_column} and {@code board_sprint} indexes on {@link Issue} hand over without a
 * sort.
 */
@Service
@RequiredArgsConstructor
public class BoardReader {

	static final int MAX_PAGE_SIZE = 100;

	/** How far into a query a page may start. Nobody scrolls a column past ten thousand cards. */
	static final int MAX_OFFSET = 10_000;

	/** Issues an epic filter, or the sub-tasks of a sprint, may reach through. */
	private static final int MAX_LINKED = 10_000;
	private static final int MAX_PEOPLE = 500;
	private static final int MAX_FACET_PEOPLE = 200;
	private static final int MAX_FACET_VALUES = 500;
	private static final int MAX_EPICS = 200;

	private static final String ISSUES = "issues";
	private static final String EPIC = Issue.Type.EPIC.name();
	private static final String SUBTASK = Issue.Type.SUBTASK.name();
	private static final Sort BOARD_ORDER = Sort.by(Sort.Order.asc("rank"), Sort.Order.asc("_id"));
	private static final Sort TIMELINE_ORDER =
			Sort.by(Sort.Order.asc("startDate"), Sort.Order.asc("dueDate"), Sort.Order.asc("_id"));

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final ProjectService projects;
	private final IssueService issueService;
	private final UserRepository users;
	private final MongoTemplate mongo;

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
	 * Where a page of cards comes from: the column of that name, the sprint, the backlog, or the
	 * whole board when none is given. [dated] splits the timeline into cards with a date and cards
	 * without.
	 */
	public record CardSource(String column, String sprintId, boolean backlog, Boolean dated) {
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
	 * The wall of a board: its columns, each with its number of cards and the first [size] of them.
	 * A [size] of 0 returns the columns alone, without counting anything.
	 */
	public BoardWall wall(String boardId, String sprintId, BoardQuery query, int size, User user) {
		int pageSize = pageSize(size);
		checkId(sprintId);
		BoardScope scope = scope(boardId, user);
		String sprint = present(sprintId) ? sprintId : scope.board().getActiveSprintId();
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(boardId);
		if (pageSize == 0) {
			List<WallColumn> bare = scope.columns().stream()
					.map(column -> column(scope, column, null, List.of()))
					.toList();
			return new BoardWall(scope.board(), boardSprints, sprint, bare, List.of(), List.of());
		}
		Optional<Criteria> criteria = criteria(scope, sprint == null ? Place.BOARD : Place.sprint(sprint), query);
		Map<String, Long> byState = criteria.map(this::countByState).orElse(Map.of());
		List<List<Issue>> pages = new ArrayList<>();
		List<Issue> loaded = new ArrayList<>();
		for (AgileBoard.Column column : scope.columns()) {
			List<Issue> page = criteria.isEmpty() || total(byState, column) == 0 ? List.of()
					: find(and(criteria.get(), stateIn(column)), BOARD_ORDER, 0, pageSize);
			pages.add(page);
			loaded.addAll(page);
		}
		Cards cards = cards(scope, loaded);
		List<WallColumn> columns = new ArrayList<>();
		for (int i = 0; i < scope.columns().size(); i++) {
			AgileBoard.Column column = scope.columns().get(i);
			columns.add(column(scope, column, total(byState, column), cards.of(pages.get(i))));
		}
		return new BoardWall(scope.board(), boardSprints, sprint, columns, cards.users(), cards.refs());
	}

	/** One page of cards of [source], narrowed by [query]. */
	public BoardCardPage cards(String boardId, CardSource source, BoardQuery query, int page, int size,
			boolean summary, User user) {
		int pageSize = pageSize(size);
		checkId(source.sprintId());
		checkId(source.column());
		if (page < 0 || source.backlog() && present(source.sprintId())) {
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
		Place place = source.backlog() ? Place.BACKLOG
				: present(source.sprintId()) ? Place.sprint(source.sprintId()) : Place.BOARD;
		Optional<Criteria> found = criteria(scope, place, query);
		if (found.isEmpty()) {
			return new BoardCardPage(List.of(), 0, page, pageSize, List.of(), List.of(),
					summary ? List.of() : null);
		}
		Criteria criteria = found.get();
		if (column != null) {
			criteria = and(criteria, stateIn(column));
		}
		if (source.dated() != null) {
			criteria = and(criteria, source.dated() ? dated() : undated());
		}
		long total = mongo.count(Query.query(criteria), Issue.class);
		long offset = (long) page * pageSize;
		Sort order = Boolean.TRUE.equals(source.dated()) ? TIMELINE_ORDER : BOARD_ORDER;
		List<Issue> content = pageSize == 0 || offset >= total || offset > MAX_OFFSET ? List.of()
				: find(criteria, order, offset, pageSize);
		Cards cards = cards(scope, content);
		return new BoardCardPage(cards.of(content), total, page, pageSize, cards.users(), cards.refs(),
				summary ? summarize(criteria) : null);
	}

	/** What the filter can offer over every card of the board, of the sprint or of the backlog. */
	public BoardFacets facets(String boardId, String sprintId, boolean backlog, User user) {
		checkId(sprintId);
		if (backlog && present(sprintId)) {
			throw invalid();
		}
		BoardScope scope = scope(boardId, user);
		Place place = backlog ? Place.BACKLOG : present(sprintId) ? Place.sprint(sprintId) : Place.BOARD;
		Criteria criteria = criteria(scope, place, BoardQuery.ALL).orElseThrow();
		Document facets = mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria), FACET_FIELDS, FACETS),
				ISSUES, Document.class).getUniqueMappedResult();
		List<String> assignees = values(facets, "assignees");
		List<String> reporters = values(facets, "reporters");

		Query epicQuery = Query.query(Criteria.where("projectId").in(scope.projectIds()).and("archived").is(false)
				.and("type").is(EPIC)).limit(MAX_EPICS);
		epicQuery.fields().include(BoardRef.FIELDS).include(Issue.PROJECTION_REQUIRED);
		List<String> order = scope.projectIds();
		List<BoardRef> epics = mongo.find(epicQuery, Issue.class).stream()
				.sorted(Comparator.<Issue>comparingInt(epic -> order.indexOf(epic.getProjectId()))
						.thenComparingLong(Issue::getNumberInProject))
				.map(BoardRef::of)
				.toList();

		return new BoardFacets(assignees, reporters, values(facets, "labels"), values(facets, "states"),
				values(facets, "types"), values(facets, "priorities"), epics,
				people(Stream.concat(assignees.stream(), reporters.stream())));
	}

	/**
	 * The board as [user] may read it: the projects of it the viewer may see, active ones only, in
	 * the board's order, and the columns built from them.
	 *
	 * @throws ApiException 404 for a board that does not exist, 403 when the viewer may see none of
	 *                      its projects. An admin reads such a board as empty.
	 */
	BoardScope scope(String boardId, User user) {
		AgileBoard board = boards.findById(boardId).orElseThrow(() -> ApiException.notFound("board"));
		List<String> spannedIds = board.getProjectIds() == null ? List.of()
				: board.getProjectIds().stream().filter(Objects::nonNull).distinct().toList();
		Map<String, Project> readable = new HashMap<>();
		for (Project project : readableProjects(spannedIds, user)) {
			if (!project.isArchived()) {
				readable.put(project.getId(), project);
			}
		}
		if (readable.isEmpty() && !user.isAdmin()) {
			throw ApiException.forbidden("error.accessDenied");
		}
		List<Project> spanned = spannedIds.stream().map(readable::get).filter(Objects::nonNull).toList();
		List<AgileBoard.Column> columns = board.hasCustomColumns()
				? BoardColumns.reconcile(board.getColumns(), spanned)
				: BoardColumns.merge(spanned);
		return new BoardScope(board, spanned, columns, BoardColumns.hues(columns, spanned));
	}

	/** The board's projects the viewer may see, named in one query while there are few enough to name. */
	private List<Project> readableProjects(List<String> ids, User user) {
		if (ids.isEmpty()) {
			return List.of();
		}
		if (ids.size() <= ProjectService.RESOLVE_CAP) {
			return projects.resolveVisible(user, ids);
		}
		Set<String> wanted = Set.copyOf(ids);
		return projects.visibleTo(user).stream().filter(project -> wanted.contains(project.getId())).toList();
	}

	/**
	 * The issues of [place] in [scope] that [query] keeps, or empty when the query names only states
	 * the board's projects do not have and so keeps nothing.
	 */
	private Optional<Criteria> criteria(BoardScope scope, Place place, BoardQuery query) {
		List<String> projectIds = scope.projectIds();
		List<Criteria> parts = new ArrayList<>();
		parts.add(Criteria.where("projectId").in(projectIds));
		parts.add(Criteria.where("archived").is(false));
		if (place.inSprint() && query.shape() == BoardQuery.Shape.SUBTASKS) {
			// A sub-task carries no sprint of its own: it is in the sprint its parent is in.
			List<String> sprintWork = idsOf(Criteria.where("projectId").in(projectIds).and("archived").is(false)
					.and("sprintId").is(place.sprintId()).and("type").nin(EPIC, SUBTASK));
			parts.add(new Criteria().orOperator(
					Criteria.where("sprintId").is(place.sprintId()).and("type").ne(EPIC),
					Criteria.where("type").is(SUBTASK).and("parentId").in(sprintWork)));
		}
		else {
			if (place.inSprint()) {
				parts.add(Criteria.where("sprintId").is(place.sprintId()));
			}
			if (place.backlog()) {
				parts.add(Criteria.where("sprintId").is(null));
			}
			parts.add(switch (query.shape()) {
				case WALL -> Criteria.where("type").nin(EPIC, SUBTASK);
				case SUBTASKS -> Criteria.where("type").ne(EPIC);
				case TIMELINE -> Criteria.where("type").ne(SUBTASK);
			});
		}
		if (query.hasText()) {
			String quoted = Pattern.quote(query.text());
			parts.add(new Criteria().orOperator(
					Criteria.where("readableId").regex(quoted, "i"),
					Criteria.where("title").regex(quoted, "i"),
					Criteria.where("tags").regex(quoted, "i")));
		}
		if (!query.states().isEmpty()) {
			Set<String> named = scope.statesNamed(query.states());
			if (named.isEmpty()) {
				return Optional.empty();
			}
			parts.add(Criteria.where("state").in(BoardScope.spellings(named)));
		}
		if (!query.types().isEmpty()) {
			parts.add(Criteria.where("type").in(names(query.types())));
		}
		if (!query.priorities().isEmpty()) {
			parts.add(Criteria.where("priority").in(names(query.priorities())));
		}
		if (!query.assigneeIds().isEmpty()) {
			parts.add(new Criteria().orOperator(
					Criteria.where("assigneeIds").in(query.assigneeIds()),
					Criteria.where("assigneeId").in(query.assigneeIds())));
		}
		if (!query.reporterIds().isEmpty()) {
			parts.add(Criteria.where("reporterId").in(query.reporterIds()));
		}
		if (!query.labels().isEmpty()) {
			parts.add(Criteria.where("tags").in(query.labels()));
		}
		if (!query.sprintIds().isEmpty() || query.noSprint()) {
			List<Criteria> anySprint = new ArrayList<>();
			if (!query.sprintIds().isEmpty()) {
				anySprint.add(Criteria.where("sprintId").in(query.sprintIds()));
			}
			if (query.noSprint()) {
				anySprint.add(Criteria.where("sprintId").is(null));
			}
			parts.add(anySprint.size() == 1 ? anySprint.getFirst() : new Criteria().orOperator(anySprint));
		}
		if (!query.epicIds().isEmpty()) {
			// A sub-task rolls up to the epic of its parent.
			List<String> children = idsOf(Criteria.where("projectId").in(projectIds).and("archived").is(false)
					.and("parentId").in(query.epicIds()));
			Criteria underEpic = Criteria.where("parentId").in(query.epicIds());
			parts.add(children.isEmpty() ? underEpic
					: new Criteria().orOperator(underEpic, Criteria.where("parentId").in(children)));
		}
		return Optional.of(new Criteria().andOperator(parts));
	}

	private List<Issue> find(Criteria criteria, Sort order, long offset, int limit) {
		Query query = Query.query(criteria).with(order).skip(offset).limit(limit);
		query.fields().include(BoardCard.FIELDS).include(Issue.PROJECTION_REQUIRED);
		return mongo.find(query, Issue.class);
	}

	/** The ids of the issues [criteria] matches, read as plain documents. */
	private List<String> idsOf(Criteria criteria) {
		Query query = Query.query(criteria).limit(MAX_LINKED);
		query.fields().include("_id");
		return mongo.find(query, Document.class, ISSUES).stream()
				.map(document -> document.get("_id").toString())
				.toList();
	}

	/** The number of issues per stored state, in one pass over the query. */
	private Map<String, Long> countByState(Criteria criteria) {
		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group("state").count().as("count"));
		Map<String, Long> counts = new HashMap<>();
		for (Document row : mongo.aggregate(aggregation, ISSUES, Document.class)) {
			Object state = row.get("_id");
			if (state != null) {
				counts.merge(state.toString(), ((Number) row.get("count")).longValue(), Long::sum);
			}
		}
		return counts;
	}

	/** The cards of [column], counted in exactly the spellings its page is read in. */
	private static long total(Map<String, Long> byState, AgileBoard.Column column) {
		return BoardScope.spellings(column.getStates()).stream()
				.mapToLong(state -> byState.getOrDefault(state, 0L))
				.sum();
	}

	private List<StateSummary> summarize(Criteria criteria) {
		List<StateSummary> summary = new ArrayList<>();
		for (Document row : mongo.aggregate(Aggregation.newAggregation(Aggregation.match(criteria), SUMMARY),
				ISSUES, Document.class)) {
			Document key = row.get("_id", Document.class);
			summary.add(new StateSummary(key.getString("state"), Boolean.TRUE.equals(key.getBoolean("resolved")),
					((Number) row.get("count")).longValue(), ((Number) row.get("points")).longValue()));
		}
		summary.sort(Comparator.comparing(StateSummary::state, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
				.thenComparing(StateSummary::resolved));
		return summary;
	}

	/** Epics, parents, sub-task counts and people for [cards], each in one query for the whole page. */
	private Cards cards(BoardScope scope, List<Issue> cards) {
		if (cards.isEmpty()) {
			return Cards.NONE;
		}
		Map<String, Issue> related = new LinkedHashMap<>();
		for (Issue parent : refs(scope, unknown(cards.stream().map(Issue::getParentId), related))) {
			related.put(parent.getId(), parent);
		}
		List<String> grandparentIds = unknown(related.values().stream()
				.filter(parent -> parent.getType() != Issue.Type.EPIC)
				.map(Issue::getParentId), related);
		for (Issue grandparent : refs(scope, grandparentIds)) {
			related.put(grandparent.getId(), grandparent);
		}
		Map<String, String> epicOf = new HashMap<>();
		for (Issue card : cards) {
			String epic = epicOf(card, related);
			if (epic != null) {
				epicOf.put(card.getId(), epic);
			}
		}
		Map<String, IssueService.SubtaskTally> tallies =
				issueService.subtaskTallies(cards.stream().map(Issue::getId).toList());
		List<DirectoryUser> people = people(cards.stream().flatMap(card -> Stream.concat(
				Stream.of(card.getAssigneeId(), card.getReporterId()),
				card.getAssigneeIds() == null ? Stream.empty() : card.getAssigneeIds().stream())));
		return new Cards(epicOf, tallies, people, related.values().stream().map(BoardRef::of).toList());
	}

	/** The epic [card] rolls up to: its parent, or for a sub-task its grandparent. */
	private static String epicOf(Issue card, Map<String, Issue> related) {
		Issue parent = card.getParentId() == null ? null : related.get(card.getParentId());
		if (parent == null) {
			return null;
		}
		if (parent.getType() == Issue.Type.EPIC) {
			return parent.getId();
		}
		Issue grandparent = parent.getParentId() == null ? null : related.get(parent.getParentId());
		return grandparent != null && grandparent.getType() == Issue.Type.EPIC ? grandparent.getId() : null;
	}

	private static List<String> unknown(Stream<String> ids, Map<String, Issue> known) {
		return ids.filter(Objects::nonNull).filter(id -> !known.containsKey(id)).distinct().toList();
	}

	/** Issues named by id, as far as they belong to the board's projects this viewer may see. */
	private List<Issue> refs(BoardScope scope, Collection<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids).and("projectId").in(scope.projectIds())
				.and("archived").is(false));
		query.fields().include(BoardRef.FIELDS).include(Issue.PROJECTION_REQUIRED);
		return mongo.find(query, Issue.class);
	}

	private List<DirectoryUser> people(Stream<String> ids) {
		List<String> wanted = ids.filter(Objects::nonNull).filter(id -> !id.isBlank()).distinct()
				.limit(MAX_PEOPLE).toList();
		if (wanted.isEmpty()) {
			return List.of();
		}
		return users.findAllById(wanted).stream().filter(User::isActive).map(DirectoryUser::from).toList();
	}

	private static WallColumn column(BoardScope scope, AgileBoard.Column column, Long total, List<BoardCard> cards) {
		return new WallColumn(column.getName(), column.getStates(), scope.wipLimit(column.getName()),
				scope.hue(column.getName()), total, cards);
	}

	private static Criteria and(Criteria first, Criteria second) {
		return new Criteria().andOperator(first, second);
	}

	private static Criteria stateIn(AgileBoard.Column column) {
		return Criteria.where("state").in(BoardScope.spellings(column.getStates()));
	}

	private static Criteria dated() {
		return new Criteria().orOperator(Criteria.where("startDate").ne(null), Criteria.where("dueDate").ne(null));
	}

	private static Criteria undated() {
		return Criteria.where("startDate").is(null).and("dueDate").is(null);
	}

	private static List<String> names(Set<? extends Enum<?>> values) {
		return values.stream().map(Enum::name).sorted().toList();
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

	/** Which issues of the board a read starts from: all of them, one sprint's, or the backlog. */
	private record Place(String sprintId, boolean backlog) {

		static final Place BOARD = new Place(null, false);
		static final Place BACKLOG = new Place(null, true);

		static Place sprint(String sprintId) {
			return new Place(sprintId, false);
		}

		boolean inSprint() {
			return sprintId != null;
		}
	}

	/** The cards of one read, with what they refer to. */
	private record Cards(Map<String, String> epicOf, Map<String, IssueService.SubtaskTally> tallies,
			List<DirectoryUser> users, List<BoardRef> refs) {

		static final Cards NONE = new Cards(Map.of(), Map.of(), List.of(), List.of());

		List<BoardCard> of(List<Issue> issues) {
			return issues.stream().map(issue -> {
				IssueService.SubtaskTally tally = tallies.getOrDefault(issue.getId(), IssueService.SubtaskTally.NONE);
				return BoardCard.of(issue, epicOf.get(issue.getId()), tally.total(), tally.done());
			}).toList();
		}
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
