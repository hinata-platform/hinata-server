package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserController.DirectoryUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a board's cards page by page, for the wall, a sprint, the backlog and the timeline, and what
 * the board's filter and its timeline need besides.
 *
 * <p>It replaces a board that loaded every issue of its projects at full weight and searched them on
 * the client. Every read here is one viewer's: the board's projects that viewer may see, active issues
 * only, narrowed on the server by the board's search and filter ({@link BoardCriteria}), in pages of at
 * most {@value #MAX_PAGE_SIZE} cards. Each read comes off the index that bounds it to the fewest cards
 * ({@link BoardCriteria#index}, read by {@link BoardIssueReads}), a page reads only the state spellings
 * its column was counted in, and neither a read nor the reads of a request together run longer than
 * {@link BoardTime} allows.
 */
@Service
@RequiredArgsConstructor
public class BoardReader {

	static final int MAX_PAGE_SIZE = 100;

	/** How far into a query a page may start. Nobody scrolls a column past ten thousand cards. */
	static final int MAX_OFFSET = 10_000;

	/** The most cards a view may ask the connectors between at once. */
	static final int MAX_LINK_CARDS = 1_000;

	/** The timeline's cards with a start date, by start date. */
	private static final Sort STARTED_ORDER =
			Sort.by(Sort.Order.asc("startDate"), Sort.Order.asc("dueDate"), Sort.Order.asc("_id"));

	/** The timeline's cards with a due date alone: every start date is empty, so the due dates order them. */
	private static final Sort STARTLESS_ORDER = Sort.by(Sort.Order.asc("dueDate"), Sort.Order.asc("_id"));

	/** The timeline's cards without a date: with no date to order them by, their ids do. */
	private static final Sort UNDATED_ORDER = Sort.by(Sort.Order.asc("_id"));

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
	 * [dated] the timeline's cards with a date or those without. A column may lie in a sprint; the
	 * timeline has no columns.
	 */
	public record CardSource(String column, String sprintId, boolean backlog, Boolean dated) {

		/** Whether the source names where its cards come from. */
		boolean named() {
			return present(column) || present(sprintId) || backlog || dated != null;
		}
	}

	/**
	 * One page of cards, with the people and references on it, and on request a summary by state. A page
	 * of the timeline carries its cards alone, see {@link BoardCard#bare}: no people and no references.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BoardCardPage(List<BoardCard> content, long totalElements, int page, int size,
			List<DirectoryUser> users, List<BoardRef> refs, List<BoardStateSummary> summary) {
	}

	/**
	 * The wall of a board: its columns, each with its number of cards and the first [size] of them. A
	 * [size] of 0 returns the columns alone, without counting anything, and so does a Scrum board
	 * between two sprints, which shows no wall.
	 */
	public BoardWall wall(String boardId, String sprintId, BoardQuery query, int size, User user) {
		return BoardTime.request(() -> readWall(boardId, sprintId, query, size, user));
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
		Optional<Criteria> criteria = BoardCriteria.of(scope, place, query, issues.lookupFor(scope.projectIds()));
		String index = BoardCriteria.index(place, query, null);
		Map<String, Long> byState = criteria.map(found -> issues.countByState(found, index)).orElse(Map.of());
		List<List<Issue>> pages = new ArrayList<>();
		List<Long> totals = new ArrayList<>();
		List<Issue> loaded = new ArrayList<>();
		for (AgileBoard.Column column : scope.columns()) {
			Map<String, Long> counted = counted(byState, column);
			List<Issue> page = counted.isEmpty() ? List.of()
					: issues.find(BoardCriteria.and(criteria.orElseThrow(), BoardCriteria.stateIn(counted.keySet())),
							BoardCriteria.BOARD_ORDER, 0, pageSize, index, BoardCard.FIELDS);
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
		return BoardTime.request(() -> readCards(boardId, source, query, page, size, summary, user));
	}

	private BoardCardPage readCards(String boardId, CardSource source, BoardQuery query, int page, int size,
			boolean summary, User user) {
		int pageSize = pageSize(size);
		checkSource(source, page, summary);
		BoardScope scope = scope(boardId, user);
		AgileBoard.Column column = namedColumn(scope, source.column());
		BoardCriteria.Place place = source.backlog() ? BoardCriteria.Place.BACKLOG
				: present(source.sprintId()) ? BoardCriteria.Place.sprint(source.sprintId()) : BoardCriteria.Place.BOARD;
		BoardCriteria.Lookup lookup = issues.lookupFor(scope.projectIds());
		Optional<Criteria> found = BoardCriteria.of(scope, place, query, lookup);
		if (found.isEmpty()) {
			return new BoardCardPage(List.of(), 0, page, pageSize, List.of(), List.of(),
					summary ? List.of() : null);
		}
		String index = BoardCriteria.index(place, query, source.dated());
		Criteria criteria = Boolean.FALSE.equals(source.dated())
				? BoardCriteria.and(found.get(), BoardCriteria.undated()) : found.get();
		Counted counted = count(criteria, index, column, source.dated(), lookup);
		long offset = (long) page * pageSize;
		List<Issue> content = readsPage(offset, counted.total(), pageSize)
				? content(counted, index, source.dated(), offset, pageSize) : List.of();
		// A sprint's summary counts the whole sprint, whatever column the page is of.
		Criteria whole = Boolean.TRUE.equals(source.dated())
				? BoardCriteria.and(criteria, BoardCriteria.dated()) : criteria;
		List<BoardStateSummary> sprintSummary = summary ? issues.summarize(whole, index) : null;
		if (source.dated() != null) {
			// The timeline draws neither sub-tasks nor epics nor people, so its cards go without them.
			return new BoardCardPage(content.stream().map(BoardCard::bare).toList(), counted.total(), page,
					pageSize, List.of(), List.of(), sprintSummary);
		}
		BoardCardAssembler.Cards cards = assembler.cards(scope, content);
		return new BoardCardPage(cards.of(content), counted.total(), page, pageSize, cards.users(), cards.refs(),
				sprintSummary);
	}

	/**
	 * Refuses a read that does not name where its cards come from, or that asks its source for what it
	 * has not. The whole board in board order has no index to come off, and no view reads it; a read
	 * names the backlog or a sprint, not both; only a sprint has a summary to show; the timeline has no
	 * columns.
	 */
	private static void checkSource(CardSource source, int page, boolean summary) {
		checkId(source.sprintId());
		checkId(source.column());
		boolean ofSprint = present(source.sprintId());
		if (page < 0 || !source.named() || (source.backlog() && ofSprint) || (summary && !ofSprint)
				|| (present(source.column()) && source.dated() != null)) {
			throw invalid();
		}
	}

	/** The column of [scope] called [name], or null when the read names no column. */
	private static AgileBoard.Column namedColumn(BoardScope scope, String name) {
		if (!present(name)) {
			return null;
		}
		AgileBoard.Column column = scope.column(name);
		if (column == null) {
			throw ApiException.badRequest("error.board.unknownColumn", name);
		}
		return column;
	}

	/**
	 * What a read counted: [total] cards, [startless] of them on the timeline with a due date alone, and
	 * the [criteria] its page reads, for a column narrowed to the spellings its states were counted in.
	 */
	private record Counted(Criteria criteria, long total, long startless) {
	}

	/**
	 * Counts the cards [criteria] matches off [index]: a column's by state, so that its page reads only
	 * the spellings found; the timeline's with a date in its two parts; any other at once.
	 */
	private Counted count(Criteria criteria, String index, AgileBoard.Column column, Boolean dated,
			BoardCriteria.Lookup lookup) {
		if (column != null) {
			Map<String, Long> counted = counted(issues.countByState(BoardCriteria.and(criteria,
					BoardCriteria.stateIn(lookup.spellings(column.getStates()))), index), column);
			return new Counted(BoardCriteria.and(criteria, BoardCriteria.stateIn(counted.keySet())), sum(counted), 0);
		}
		if (Boolean.TRUE.equals(dated)) {
			long startless = issues.count(BoardCriteria.and(criteria, BoardCriteria.startless()), index);
			long started = issues.count(BoardCriteria.and(criteria, BoardCriteria.started()), index);
			return new Counted(criteria, startless + started, startless);
		}
		return new Counted(criteria, issues.count(criteria, index), 0);
	}

	/** The page from [offset] of the cards [counted] counted, in the order of their source. */
	private List<Issue> content(Counted counted, String index, Boolean dated, long offset, int pageSize) {
		if (Boolean.TRUE.equals(dated)) {
			return datedPage(counted.criteria(), index, offset, pageSize, counted.startless());
		}
		Sort order = dated != null ? UNDATED_ORDER : BoardCriteria.BOARD_ORDER;
		return issues.find(counted.criteria(), order, offset, pageSize, index, BoardCard.FIELDS);
	}

	/**
	 * A page of the timeline's cards with a date: those with a due date alone first, by due date, then
	 * those with a start date, by start date, which is the order one sort over all of them gives.
	 * [startless] counts the first part. Each part comes off the timeline index in its own order, which
	 * the index keeps across the scans of several projects; one read of both parts would have to sort
	 * every dated card of the board for a page.
	 */
	private List<Issue> datedPage(Criteria criteria, String index, long offset, int pageSize, long startless) {
		List<Issue> page = new ArrayList<>();
		if (offset < startless) {
			page.addAll(issues.find(BoardCriteria.and(criteria, BoardCriteria.startless()), STARTLESS_ORDER, offset,
					pageSize, index, BoardCard.FIELDS));
		}
		if (page.size() < pageSize) {
			page.addAll(issues.find(BoardCriteria.and(criteria, BoardCriteria.started()), STARTED_ORDER,
					Math.max(0, offset - startless), pageSize - page.size(), index, BoardCard.FIELDS));
		}
		return page;
	}

	/**
	 * What the filter and the row of faces can offer over every active card of the board's projects
	 * the viewer may see, for the cards of [shape]. See {@link BoardFacetsReader}.
	 */
	public BoardFacets facets(String boardId, BoardQuery.Shape shape, User user) {
		return BoardTime.request(() -> facetsReader.facets(scope(boardId, user), shape));
	}

	/**
	 * The connectors between the cards [ids] of the board that a view holds, both ends among them:
	 * what a timeline draws. A card of a project the viewer may not see is no end of any.
	 */
	public List<IssueLinkGraphService.LinkEdge> links(String boardId, List<String> ids, User user) {
		return BoardTime.request(() -> readLinks(boardId, ids, user));
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
		return graph.among(issues.dependencies(wanted, scope.projectIds()), BoardTime.left());
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

	/** The spellings the active issues of [scope] store [states] in, see {@link BoardCriteria#spellings}. */
	Set<String> spellings(BoardScope scope, Collection<String> states) {
		return issues.lookupFor(scope.projectIds()).spellings(states);
	}

	/**
	 * Up to [perProject] active issues of each of [scope]'s projects, whole and each project's in board
	 * order: of the sprint [sprintId], or else in [states]. What the board view before the paged wall
	 * reads, every read within the time of its request.
	 */
	List<Issue> wholeCards(BoardScope scope, String sprintId, Collection<String> states, int perProject) {
		List<Issue> cards = new ArrayList<>();
		for (Project project : scope.projects()) {
			cards.addAll(issues.wholeCards(project.getId(), sprintId, states, perProject));
		}
		return cards;
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
