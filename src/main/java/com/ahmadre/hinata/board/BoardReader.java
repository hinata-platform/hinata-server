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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.function.SingletonSupplier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Reads a board's cards page by page, for the wall, a sprint, the backlog and the timeline, and what
 * the board's filter and its timeline need besides.
 *
 * <p>It replaces a board that loaded every issue of its projects at full weight and searched them on
 * the client. Every read here is one viewer's: the board's projects that viewer may see, active issues
 * only, narrowed on the server by the board's search and filter ({@link BoardCriteria}), in pages of at
 * most {@value #MAX_PAGE_SIZE} cards. Each read comes off the index that bounds it to the fewest cards
 * ({@link BoardCriteria#index}, read by {@link BoardIssueReads}), a page reads only the state spellings
 * its column was counted in, and no read runs longer than {@link BoardIssueReads#MAX_TIME}.
 */
@Service
@RequiredArgsConstructor
public class BoardReader {

	static final int MAX_PAGE_SIZE = 100;

	/** How far into a query a page may start. Nobody scrolls a column past ten thousand cards. */
	static final int MAX_OFFSET = 10_000;

	/** The most cards a view may ask the connectors between at once. */
	static final int MAX_LINK_CARDS = 1_000;

	private static final Sort BOARD_ORDER = Sort.by(Sort.Order.asc("rank"), Sort.Order.asc("_id"));
	private static final Sort TIMELINE_ORDER =
			Sort.by(Sort.Order.asc("startDate"), Sort.Order.asc("dueDate"), Sort.Order.asc("_id"));

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final BoardAccess access;
	private final BoardCardAssembler assembler;
	private final BoardIssueReads issues;
	private final BoardFacetsReader facetsReader;
	private final IssueLinkGraphService graph;

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
		Optional<Criteria> criteria = BoardCriteria.of(scope, place, query, issues::idsOf);
		String index = BoardCriteria.index(place, query, null);
		Map<String, Long> byState = criteria.map(found -> issues.countByState(found, index)).orElse(Map.of());
		List<List<Issue>> pages = new ArrayList<>();
		List<Long> totals = new ArrayList<>();
		List<Issue> loaded = new ArrayList<>();
		for (AgileBoard.Column column : scope.columns()) {
			Map<String, Long> counted = counted(byState, column);
			List<Issue> page = counted.isEmpty() ? List.of()
					: issues.find(BoardCriteria.and(criteria.orElseThrow(), BoardCriteria.stateIn(counted.keySet())),
							BOARD_ORDER, 0, pageSize, index, BoardCard.FIELDS);
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
		Optional<Criteria> found = BoardCriteria.of(scope, place, query, issues::idsOf);
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
			Map<String, Long> counted = counted(issues.countByState(BoardCriteria.and(criteria,
					BoardCriteria.stateIn(scope.spellings(column.getStates()))), index), column);
			total = sum(counted);
			criteria = BoardCriteria.and(criteria, BoardCriteria.stateIn(counted.keySet()));
		}
		else {
			total = issues.count(criteria, index);
		}
		long offset = (long) page * pageSize;
		Sort order = source.dated() != null ? TIMELINE_ORDER : BOARD_ORDER;
		List<Issue> content = readsPage(offset, total, pageSize)
				? issues.find(criteria, order, offset, pageSize, index, BoardCard.FIELDS)
				: List.of();
		BoardCardAssembler.Cards cards = assembler.cards(scope, content);
		return new BoardCardPage(cards.of(content), total, page, pageSize, cards.users(), cards.refs(),
				summary ? issues.summarize(scoped, index) : null);
	}

	/**
	 * What the filter and the row of faces can offer over every active card of the board's projects
	 * the viewer may see, for the cards of [shape]. See {@link BoardFacetsReader}.
	 */
	public BoardFacets facets(String boardId, BoardQuery.Shape shape, User user) {
		return timed(() -> facetsReader.facets(scope(boardId, user), shape));
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
		return graph.among(issues.dependencies(wanted, scope.projectIds()));
	}

	/**
	 * The board as [user] may read it: the projects of it the viewer may see, active ones only, in the
	 * board's order, the columns built from them, and the spellings their active issues store the
	 * states in, asked of the database only once a read matches states.
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
		List<String> projectIds = spanned.stream().map(Project::getId).toList();
		Supplier<Set<String>> storedStates = SingletonSupplier.of(
				() -> Set.copyOf(issues.distinct("state", projectIds, BoardCriteria.BY_STATE)));
		return new BoardScope(board, spanned, columns, BoardColumns.hues(columns, spanned), storedStates);
	}

	/**
	 * Runs [read] and says the server is busy when the database gave up on it after
	 * {@link BoardIssueReads#MAX_TIME}: a read that took too long ends in a 503 the app can explain
	 * rather than in a 500.
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

	/**
	 * The counts of the stored spellings of [column]'s states, matched ignoring case: the spellings its
	 * page reads in.
	 */
	private static Map<String, Long> counted(Map<String, Long> byState, AgileBoard.Column column) {
		Map<String, Long> counted = new LinkedHashMap<>();
		new TreeMap<>(byState).forEach((stored, count) -> {
			if (count > 0 && column.getStates().stream().anyMatch(state -> state != null && state.equalsIgnoreCase(stored))) {
				counted.put(stored, count);
			}
		});
		return counted;
	}

	private static long sum(Map<String, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

	private static WallColumn column(BoardScope scope, AgileBoard.Column column, Long total, List<BoardCard> cards) {
		return new WallColumn(column.getName(), column.getStates(), scope.wipLimit(column.getName()),
				scope.hue(column.getName()), total, cards);
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
}
