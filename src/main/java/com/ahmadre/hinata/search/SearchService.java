package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleAccess;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.BoardLinks;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchResponse.SearchGroup;
import com.ahmadre.hinata.user.User;
import com.mongodb.MongoExecutionTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Unified global search across Issues, Projects, People, Boards & Sprints and
 * Knowledge — the backend for the ⌘K palette.
 *
 * <p><b>Hybrid Mongo-native strategy.</b> For each collection we run two cheap,
 * index-backed, bounded queries and merge them:
 * <ul>
 *   <li>a case-insensitive <b>regex</b> over the short label fields — contains
 *       on names/titles, anchored prefix on ids/keys — which gives true
 *       as-you-type and partial-id matching ({@code HIV-23 → HIV-231},
 *       {@code len → Lena}); and</li>
 *   <li>a Mongo <b>$text</b> query over the per-collection text index (created
 *       from {@code @TextIndexed} fields incl. descriptions / article content)
 *       for stemmed, relevance-ranked full-text matches.</li>
 * </ul>
 * Results are deduped by id, prefix matches floated to the top, then capped.
 * No external search engine — fits the self-hosted MongoDB deployment.
 *
 * <p><b>Reach.</b> Every query carries the caller's reach as a filter: issues,
 * projects, boards and sprints only of projects they see, pages only those
 * {@link ArticleAccess} opens to them. The counts are counted the same way. People
 * are the directory every picker shows, active accounts only, counted the same way. Before this, the
 * palette answered every account with everybody's issues, projects and pages.
 *
 * <p><b>Every other category</b> (HIN-165) comes from a {@link SearchSource}: comments,
 * teams, spaces and attachments from this package, shift planning, time, absences and
 * billing from their modules, each asked only while its module is on. All categories run
 * side by side on the {@link SearchExecutor}, each capped on its own.
 *
 * <p><b>Time.</b> A search has {@link #PART_TIMEOUT}, counted from when its reach is known, and
 * every read it sends takes what is left as its {@code maxTime} ({@link SearchContext#bounded}).
 * A category that is not done by then is left out, and the database stops its read.
 *
 * <p><b>What a keystroke costs.</b> The counts are worked out only when the palette opens (no
 * text, every category, not the archive). The categories of the sources are asked from
 * {@link #SOURCE_MIN_QUERY} characters on; one character is answered by the five built-in
 * categories, whose prefix matches are what a single letter is good for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

	/** Per-group cap in "all" scope; the design shows up to 5 per group. */
	private static final int CAP_ALL = 5;
	/** Per-group cap when a single category is selected. */
	private static final int CAP_SCOPED = 24;
	/** Over-fetch factor before ranking/dedupe so the cap survives merging. */
	private static final int CANDIDATE_FACTOR = 3;
	/** How many recent entities to suggest for an empty scoped query. */
	private static final int SUGGEST_LIMIT = 10;

	/**
	 * How long a search waits for its categories. Each runs on its own thread from the start, so
	 * this is the slowest one's limit, not a sum (HIN-165).
	 */
	static final Duration PART_TIMEOUT = Duration.ofSeconds(3);

	/** The shortest text the categories of the sources are asked for (HIN-165). */
	static final int SOURCE_MIN_QUERY = 2;

	private static final String F_TITLE = "title";
	private static final String F_NAME = "name";
	private static final String F_TAGS = "tags";
	private static final String F_UPDATED = "updatedAt";

	private final MongoTemplate mongo;
	private final ProjectService projectService;
	private final ArticleAccess articleAccess;

	/** The categories that run side by side; see {@link SearchExecutor}. */
	private final SearchExecutor executor;
	/** Every category a package other than this one provides, module ones included (HIN-165). */
	private final List<SearchSource> sources;

	/**
	 * The caller's reach, worked out once per search: active projects always, the
	 * archived ones only for an archive search, and the pages from the same project
	 * set rather than a second lookup of it.
	 */
	private SearchContext contextOf(User user, boolean archivedSearch, String query) {
		Map<String, Project> active = new LinkedHashMap<>();
		projectService.visibleTo(user).forEach(p -> active.put(p.getId(), p));
		Set<String> archived = archivedSearch ? projectService.archivedVisibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet()) : Set.of();
		return new SearchContext(user, active, archived,
				articleAccess.sightOf(user, active.keySet()).criteria(), query, PART_TIMEOUT);
	}

	public SearchResponse search(User user, String rawQuery, String scope) {
		return search(user, rawQuery, scope, false);
	}

	/**
	 * @param archived search the archive instead: archived (soft-deleted)
	 *                 issues and archived projects, badged by the client. Only
	 *                 those two categories exist in the archive; the rest stay
	 *                 empty. An empty query suggests the latest archived items.
	 */
	public SearchResponse search(User user, String rawQuery, String scope, boolean archived) {
		return search(user, rawQuery, scope, archived, 1);
	}

	/**
	 * @param version the client's revision of the hit contract. From 2 on a person links to
	 *                their profile ({@code /people/{id}}); before it to the user administration,
	 *                which is all a published app that predates the profile can open.
	 */
	public SearchResponse search(User user, String rawQuery, String scope, boolean archived, int version) {
		String q = rawQuery == null ? "" : rawQuery.trim();
		SearchContext reach = contextOf(user, archived, q);
		SearchCategory only = SearchCategory.parse(scope);
		int cap = only == null ? CAP_ALL : CAP_SCOPED;

		// Empty query + a specific entity scope → suggest the latest entities.
		// Empty + "all" (or Commands) → no groups (the client shows recents) —
		// except in archive mode, where the bare keyword should already reveal
		// the latest archived items.
		if (!q.isBlank()) {
			return new SearchResponse(queryGroups(reach, q, only, cap, archived, version), Map.of(), List.of());
		}
		if (only != null || archived) {
			return new SearchResponse(suggestGroups(reach, only, archived, version), Map.of(), List.of());
		}
		// The counts do not depend on what is typed or which chip is picked: they are worked out
		// when the palette opens (no text, every category, not the archive) and not again on a
		// keystroke or a change of scope after it; the client keeps them (HIN-165).
		List<String> capped = new ArrayList<>();
		return new SearchResponse(List.of(), counts(reach, capped), capped);
	}

	private List<SearchGroup> queryGroups(SearchContext reach, String q, SearchCategory only, int cap,
			boolean archived, int version) {
		Map<SearchCategory, List<Supplier<List<SearchHit>>>> tasks = new LinkedHashMap<>();
		for (SearchCategory cat : SearchCategory.values()) {
			if (only != null && cat != only) continue;
			List<Supplier<List<SearchHit>>> parts = new ArrayList<>();
			// One more than shown, so the group can say whether there is more.
			Supplier<List<SearchHit>> builtIn = builtIn(reach, cat, cap + 1, archived, version);
			if (builtIn != null) parts.add(builtIn);
			// Nothing but issues and projects has an archive.
			if (!archived && q.length() >= SOURCE_MIN_QUERY) {
				for (SearchSource source : sourcesOf(cat)) parts.add(() -> source.search(reach, cap + 1));
			}
			if (!parts.isEmpty()) tasks.put(cat, parts);
		}
		return run(reach, tasks, cap);
	}

	/** The five categories this class answers itself, or null for one it leaves to the sources. */
	private Supplier<List<SearchHit>> builtIn(SearchContext reach, SearchCategory cat, int cap,
			boolean archived, int version) {
		return switch (cat) {
			case ISSUES -> () -> mapIssues(reach, searchIssues(reach, cap, archived), archived);
			case PROJECTS -> () -> mapProjects(reach, searchProjects(reach, cap, archived), archived);
			// People, boards and docs have no archive — hidden in archive mode.
			case PEOPLE -> archived ? null : () -> mapPeople(searchPeople(reach, cap), version);
			case BOARDS -> archived ? null : () -> searchBoards(reach, cap);
			case DOCS -> archived ? null : () -> mapDocs(reach, searchDocs(reach, cap));
			default -> null;
		};
	}

	/** The sources of [cat] whose module is on, asked once per search. */
	private List<SearchSource> sourcesOf(SearchCategory cat) {
		return sources.stream().filter(s -> s.category() == cat && s.enabled()).toList();
	}

	/**
	 * Runs every part of every category at once (at most {@link SearchExecutor#PER_SEARCH} at a
	 * time) and collects them in category order, at most [cap] per group. Each part was asked for
	 * one hit more than that, so a group knows whether there is more ({@link SearchGroup#more()}).
	 * A part that fails or is not done within the search's time answers nothing, and the others
	 * answer as usual: one slow collection never holds up the palette.
	 */
	private List<SearchGroup> run(SearchContext reach, Map<SearchCategory, List<Supplier<List<SearchHit>>>> tasks,
			int cap) {
		SearchExecutor.Lane lane = executor.lane(reach);
		Map<SearchCategory, List<CompletableFuture<List<SearchHit>>>> running = new LinkedHashMap<>();
		tasks.forEach((cat, parts) -> running.put(cat, parts.stream().map(lane::supply).toList()));
		List<SearchGroup> groups = new ArrayList<>();
		running.forEach((cat, parts) -> {
			List<SearchHit> hits = new ArrayList<>();
			for (CompletableFuture<List<SearchHit>> part : parts) {
				hits.addAll(await(cat, part, reach));
			}
			if (!hits.isEmpty()) {
				boolean more = hits.size() > cap;
				groups.add(new SearchGroup(cat.name(), more ? List.copyOf(hits.subList(0, cap)) : hits, more));
			}
		});
		return groups;
	}

	private static <T> List<T> await(SearchCategory cat, CompletableFuture<List<T>> part, SearchContext reach) {
		try {
			List<T> hits = part.get(Math.max(0, reach.nanosLeft()), TimeUnit.NANOSECONDS);
			return hits == null ? List.of() : hits;
		} catch (TimeoutException e) {
			// The read itself carries the same limit, so the database stops it as well.
			part.cancel(true);
			log.warn("Search category {} took longer than {} and was left out", cat, PART_TIMEOUT);
			return List.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (ExecutionException e) {
			if (outOfTime(e.getCause())) {
				log.warn("Search category {} took longer than {} and was left out", cat, PART_TIMEOUT);
			} else {
				log.warn("Search category {} failed and was left out", cat, e.getCause());
			}
			return List.of();
		}
	}

	/** Whether [failure] is the search's time running out, here or in the database. */
	static boolean outOfTime(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SearchContext.OutOfTime || cause instanceof MongoExecutionTimeoutException) {
				return true;
			}
		}
		return false;
	}

	/** Empty-query suggestions: latest entities of one scope, or — in archive
	 * mode — the latest archived issues and projects across both categories. */
	private List<SearchGroup> suggestGroups(SearchContext reach, SearchCategory only, boolean archived,
			int version) {
		// One more than shown, so the group can say whether there is more.
		int limit = SUGGEST_LIMIT + 1;
		Map<SearchCategory, List<Supplier<List<SearchHit>>>> tasks = new LinkedHashMap<>();
		if (!archived) {
			Supplier<List<SearchHit>> builtIn = suggest(reach, only, limit, version);
			List<Supplier<List<SearchHit>>> parts = new ArrayList<>();
			if (builtIn != null) parts.add(builtIn);
			for (SearchSource source : sourcesOf(only)) parts.add(() -> source.suggest(reach, limit));
			tasks.put(only, parts);
			return run(reach, tasks, SUGGEST_LIMIT);
		}
		if (only == null || only == SearchCategory.ISSUES) {
			tasks.put(SearchCategory.ISSUES, List.of(() -> mapIssues(reach,
					latest(reach, Issue.class, limit, F_UPDATED, reach.issues(true)), true)));
		}
		if (only == null || only == SearchCategory.PROJECTS) {
			tasks.put(SearchCategory.PROJECTS, List.of(() -> mapProjects(reach,
					latest(reach, Project.class, limit, F_UPDATED, reach.projects(true)), true)));
		}
		return run(reach, tasks, SUGGEST_LIMIT);
	}

	/** Latest entities of [cat] (most-recent first) for the empty-query state; null for a source's. */
	private Supplier<List<SearchHit>> suggest(SearchContext reach, SearchCategory cat, int limit, int version) {
		return switch (cat) {
			case ISSUES -> () -> mapIssues(reach,
					latest(reach, Issue.class, limit, F_UPDATED, reach.issues(false)), false);
			case PROJECTS -> () -> mapProjects(reach,
					latest(reach, Project.class, limit, F_UPDATED, reach.projects(false)), false);
			case PEOPLE -> () -> mapPeople(
					latest(reach, User.class, limit, F_UPDATED, Criteria.where("active").is(true)), version);
			case BOARDS -> () -> suggestBoards(reach, limit);
			case DOCS -> () -> mapDocs(reach,
					latest(reach, Article.class, limit, F_UPDATED, reach.pages()));
			default -> null;
		};
	}

	// ─────────────────────────── per-category ─────────────────────────────

	private List<Issue> searchIssues(SearchContext reach, int cap, boolean archived) {
		return hybrid(reach, Issue.class, cap,
				List.of(reach.contains(F_TITLE), reach.prefix("readableId"), reach.contains(F_TAGS)),
				reach.issues(archived), F_UPDATED, Issue::getId, Issue::getTitle);
	}

	// Archived projects are hidden platform-wide; the reach holds active projects
	// only, so their issues and boards never reach the mapping below.
	private List<SearchHit> mapIssues(SearchContext reach, List<Issue> hits, boolean archived) {
		Map<String, User> byId = userMap(reach, hits.stream()
				.map(Issue::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet()));

		return hits.stream().map(it -> {
			User assignee = it.getAssigneeId() == null ? null : byId.get(it.getAssigneeId());
			return SearchHit.builder()
					.category(SearchCategory.ISSUES.name())
					.id(it.getId())
					.route("/issues/" + it.getId())
					.archived(archived ? Boolean.TRUE : null)
					.title(it.getTitle())
					.readableId(it.getReadableId())
					.type(it.getType() != null ? it.getType().name() : "TASK")
					.state(it.getState())
					.assigneeName(assignee != null ? assignee.getDisplayName() : null)
					.assigneeAvatarUrl(assignee != null ? assignee.getAvatarUrl() : null)
					.build();
		}).toList();
	}

	private List<Project> searchProjects(SearchContext reach, int cap, boolean archived) {
		return hybrid(reach, Project.class, cap,
				List.of(reach.contains(F_NAME), reach.prefix("key")),
				reach.projects(archived),
				F_UPDATED, Project::getId, Project::getName);
	}

	private List<SearchHit> mapProjects(SearchContext reach, List<Project> rawHits, boolean archived) {
		List<Project> hits = rawHits.stream().filter(p -> p.isArchived() == archived).toList();
		Map<String, User> byId = userMap(reach, hits.stream()
				.flatMap(p -> p.getMemberIds().stream()).collect(Collectors.toSet()));
		Map<String, Map<String, Long>> states = issueStates(reach, hits);

		return hits.stream().map(p -> {
			Map<String, Long> byState = states.getOrDefault(p.getId(), Map.of());
			long total = byState.values().stream().mapToLong(Long::longValue).sum();
			long done = p.getResolvedStates().stream().mapToLong(state -> byState.getOrDefault(state, 0L)).sum();
			List<String> memberNames = p.getMemberIds().stream()
					.map(id -> byId.containsKey(id) ? byId.get(id).getDisplayName() : id)
					.toList();
			return SearchHit.builder()
					.category(SearchCategory.PROJECTS.name())
					.id(p.getId())
					// Active → the project's issue list (mirrors tapping its card on
					// the Projects screen). Archived → its settings page, the only
					// meaningful destination (its issues are hidden platform-wide,
					// and that's where it can be restored).
					.route(archived
							? "/projects/" + p.getId() + "/settings"
							: "/issues?projectId=" + p.getId())
					.archived(archived ? Boolean.TRUE : null)
					.title(p.getName())
					.projectKey(p.getKey())
					.projectColor(p.getColor())
					.openCount((int) Math.max(0, total - done))
					.doneCount((int) done)
					.memberNames(memberNames)
					.build();
		}).toList();
	}

	/**
	 * How many issues each of [hits] holds per state, in one aggregation for all of them: a project
	 * hit shows its open and done issues, and which states count as done differs per project.
	 */
	private Map<String, Map<String, Long>> issueStates(SearchContext reach, List<Project> hits) {
		if (hits.isEmpty()) return Map.of();
		List<org.bson.Document> pipeline = List.of(
				new org.bson.Document("$match", new org.bson.Document("projectId",
						new org.bson.Document("$in", hits.stream().map(Project::getId).toList()))),
				new org.bson.Document("$group", new org.bson.Document("_id",
						new org.bson.Document("project", "$projectId").append("state", "$state"))
						.append("n", new org.bson.Document("$sum", 1))));
		Map<String, Map<String, Long>> byProject = new HashMap<>();
		for (org.bson.Document row : mongo.getCollection(mongo.getCollectionName(Issue.class)).aggregate(pipeline)
				.maxTime(reach.timeLeftMillis(), TimeUnit.MILLISECONDS)) {
			org.bson.Document key = row.get("_id", org.bson.Document.class);
			byProject.computeIfAbsent(key.getString("project"), id -> new HashMap<>())
					.merge(String.valueOf(key.get("state")), ((Number) row.get("n")).longValue(), Long::sum);
		}
		return byProject;
	}

	private List<User> searchPeople(SearchContext reach, int cap) {
		return hybrid(reach, User.class, cap,
				List.of(reach.contains("displayName"), reach.contains("username"), reach.contains(F_TITLE)),
				Criteria.where("active").is(true), null, User::getId, User::getDisplayName);
	}

	private List<SearchHit> mapPeople(List<User> hits, int version) {
		return hits.stream()
				.filter(User::isActive)
				.map(u -> SearchHit.builder()
						.category(SearchCategory.PEOPLE.name())
						.id(u.getId())
						.route(version >= 2 ? "/people/" + u.getId() : "/admin/users")
						.title(u.getDisplayName())
						.subtitle(u.getTitle())
						.username(u.getUsername())
						.avatarUrl(u.getAvatarUrl())
						.build())
				.toList();
	}

	private List<SearchHit> searchBoards(SearchContext reach, int cap) {
		List<AgileBoard> boards = hybrid(reach, AgileBoard.class, cap,
				List.of(reach.contains(F_NAME)), reach.boards(), null, AgileBoard::getId, AgileBoard::getName);
		List<Sprint> sprints = hybrid(reach, Sprint.class, cap,
				List.of(reach.contains(F_NAME), reach.contains("goal")), sprintsOf(reach), null,
				Sprint::getId, Sprint::getName);
		return combineBoards(boards, sprints, cap);
	}

	private List<SearchHit> suggestBoards(SearchContext reach, int limit) {
		List<AgileBoard> boards = latest(reach, AgileBoard.class, limit, "createdAt", reach.boards());
		int remaining = limit - boards.size();
		List<Sprint> sprints = remaining > 0
				? latest(reach, Sprint.class, remaining, "createdAt", sprintsOf(reach))
				: List.of();
		return combineBoards(boards, sprints, limit);
	}

	/** Sprints of the boards the caller reaches — a sprint has no project of its own. */
	private Criteria sprintsOf(SearchContext reach) {
		return Criteria.where("boardId").in(boardIds(reach));
	}

	private Set<String> boardIds(SearchContext reach) {
		Query query = reach.bounded(Query.query(reach.boards()));
		query.fields().include("_id");
		// Raw rows: a projection would hand the entity's constructor nulls for fields it
		// does not read.
		return mongo.find(query, org.bson.Document.class, "agile_boards").stream()
				.map(com.ahmadre.hinata.common.MongoIds::of)
				.collect(Collectors.toSet());
	}

	private List<SearchHit> combineBoards(List<AgileBoard> boards, List<Sprint> sprints, int cap) {
		List<SearchHit> out = new ArrayList<>();
		boards.forEach(b -> out.add(mapBoard(b)));
		sprints.forEach(s -> out.add(mapSprint(s)));
		return out.size() > cap ? out.subList(0, cap) : out;
	}

	private SearchHit mapBoard(AgileBoard b) {
		return SearchHit.builder()
				.category(SearchCategory.BOARDS.name())
				.id(b.getId())
				.kind("BOARD")
				.route(BoardLinks.of(b.getId()))
				.title(b.getName())
				.subtitle("Agile board")
				.build();
	}

	private SearchHit mapSprint(Sprint s) {
		return SearchHit.builder()
				.category(SearchCategory.BOARDS.name())
				.id(s.getId())
				// A sprint has no page of its own; it is planned and run on its board.
				.kind("SPRINT")
				// `sprint` names the sprint for a client that opens it; one that does not
				// reads the board's address and ignores the rest.
				.route(BoardLinks.of(s.getBoardId()) + "?sprint=" + s.getId())
				.title(s.getName())
				.subtitle(s.getGoal() != null && !s.getGoal().isBlank() ? s.getGoal() : "Sprint")
				.build();
	}

	private List<Article> searchDocs(SearchContext reach, int cap) {
		return hybrid(reach, Article.class, cap,
				List.of(reach.contains(F_TITLE), reach.contains(F_TAGS)),
				reach.pages(), F_UPDATED, Article::getId, Article::getTitle);
	}

	private List<SearchHit> mapDocs(SearchContext reach, List<Article> hits) {
		Map<String, Project> byId = projectMap(reach, hits.stream()
				.map(Article::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet()));

		return hits.stream().map(a -> SearchHit.builder()
				.category(SearchCategory.DOCS.name())
				.kind("ARTICLE")
				.id(a.getId())
				.route("/knowledge/" + a.getId())
				.title(a.getTitle())
				.space(a.getProjectId() != null && byId.containsKey(a.getProjectId())
						? byId.get(a.getProjectId()).getName()
						: "Knowledge")
				.updatedAt(a.getUpdatedAt())
				.build()).toList();
	}

	// ─────────────────────────── hybrid core ──────────────────────────────

	/**
	 * Runs the regex + $text pair with an AND [filter] (the caller's reach, and the
	 * archived flag) on both, merges (regex first, then text), dedupes by
	 * {@code idFn}, floats prefix matches on {@code labelFn} to the top and caps.
	 */
	private <T> List<T> hybrid(SearchContext reach, Class<T> type, int cap, List<Criteria> regexOrs,
			Criteria filter, String sortField, Function<T, String> idFn,
			Function<T, String> labelFn) {
		String q = reach.query();
		int candidates = cap * CANDIDATE_FACTOR;

		Criteria anyLabel = new Criteria().orOperator(regexOrs.toArray(Criteria[]::new));
		// One criteria object: the reach filter is itself an $and/$or, and a query holds
		// only one key-less criteria next to the label $or.
		Query regexQuery = new Query(filter == null ? anyLabel : new Criteria().andOperator(anyLabel, filter))
				.limit(candidates);
		if (sortField != null) regexQuery.with(Sort.by(Sort.Direction.DESC, sortField));
		List<T> regexHits = mongo.find(reach.bounded(regexQuery), type);

		List<T> textHits = List.of();
		if (q.length() >= 2) {
			try {
				TextCriteria tc = TextCriteria.forDefaultLanguage().matchingAny(q.split("\\s+"));
				TextQuery textQuery = TextQuery.queryText(tc).sortByScore();
				if (filter != null) textQuery.addCriteria(filter);
				textQuery.limit(candidates);
				textHits = mongo.find(reach.bounded(textQuery), type);
			} catch (RuntimeException e) {
				if (outOfTime(e)) throw e;
				// No usable text index / unsupported term — regex already covers it.
			}
		}

		LinkedHashMap<String, T> merged = new LinkedHashMap<>();
		for (T t : regexHits) merged.putIfAbsent(idFn.apply(t), t);
		for (T t : textHits) merged.putIfAbsent(idFn.apply(t), t);

		String lq = q.toLowerCase();
		return merged.values().stream()
				.sorted(Comparator.<T>comparingInt(t -> {
					String label = labelFn.apply(t);
					return label != null && label.toLowerCase().startsWith(lq) ? 0 : 1;
				}))
				.limit(cap)
				.toList();
	}

	/**
	 * Counts within the caller's reach, every category side by side: the five original ones exactly,
	 * as before, and every source's capped at {@link SearchSource#COUNT_CAP}, those that reach it
	 * listed in [capped]. A count that fails or runs out of time is left out, like a category.
	 */
	private Map<String, Long> counts(SearchContext reach, List<String> capped) {
		SearchExecutor.Lane lane = executor.lane(reach);
		CompletableFuture<Map<String, Long>> exact = lane.supply(() -> exactCounts(reach));
		Map<SearchCategory, List<CompletableFuture<Long>>> parts = new LinkedHashMap<>();
		for (SearchSource source : sources) {
			if (!source.enabled()) continue;
			parts.computeIfAbsent(source.category(), cat -> new ArrayList<>())
					.add(lane.supply(() -> source.count(reach, SearchSource.COUNT_CAP)));
		}
		Map<String, Long> totals = new LinkedHashMap<>();
		List<Map<String, Long>> base = await(SearchCategory.ISSUES, exact.thenApply(List::of), reach);
		Map<String, Long> exactCounts = base.isEmpty() ? Map.of() : base.getFirst();
		for (SearchCategory cat : SearchCategory.values()) {
			Long known = exactCounts.get(cat.name());
			long extra = 0;
			for (CompletableFuture<Long> part : parts.getOrDefault(cat, List.of())) {
				List<Long> value = await(cat, part.thenApply(List::of), reach);
				extra += value.isEmpty() ? 0 : value.getFirst();
			}
			if (known == null && !parts.containsKey(cat)) continue;
			if (extra >= SearchSource.COUNT_CAP) {
				extra = SearchSource.COUNT_CAP;
				capped.add(cat.name());
			}
			totals.put(cat.name(), (known == null ? 0 : known) + extra);
		}
		return totals;
	}

	/** The five original categories, counted exactly; a total over everybody's data would say how
	 * much there is that they cannot see. */
	private Map<String, Long> exactCounts(SearchContext reach) {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(SearchCategory.ISSUES.name(), mongo.count(reach.bounded(Query.query(reach.issues(false))), Issue.class));
		counts.put(SearchCategory.PROJECTS.name(), (long) reach.projectIds().size());
		counts.put(SearchCategory.PEOPLE.name(),
				mongo.count(reach.bounded(Query.query(Criteria.where("active").is(true))), User.class));
		Set<String> boards = boardIds(reach);
		counts.put(SearchCategory.BOARDS.name(), boards.size()
				+ mongo.count(reach.bounded(Query.query(Criteria.where("boardId").in(boards))), Sprint.class));
		counts.put(SearchCategory.DOCS.name(), mongo.count(reach.bounded(Query.query(reach.pages())), Article.class));
		return counts;
	}

	// ─────────────────────────── helpers ──────────────────────────────────

	/** The most-recent [limit] entities by [sortField] (optional [filter]). */
	private <T> List<T> latest(SearchContext reach, Class<T> type, int limit, String sortField, Criteria filter) {
		Query query = filter != null ? new Query(filter) : new Query();
		query.with(Sort.by(Sort.Direction.DESC, sortField)).limit(limit);
		return mongo.find(reach.bounded(query), type);
	}

	private Map<String, User> userMap(SearchContext reach, Collection<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, User> map = new HashMap<>();
		mongo.find(reach.bounded(Query.query(Criteria.where("_id").in(ids))), User.class)
				.forEach(u -> map.put(u.getId(), u));
		return map;
	}

	private Map<String, Project> projectMap(SearchContext reach, Set<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, Project> map = new HashMap<>();
		mongo.find(reach.bounded(Query.query(Criteria.where("_id").in(ids))), Project.class)
				.forEach(p -> map.put(p.getId(), p));
		return map;
	}
}
