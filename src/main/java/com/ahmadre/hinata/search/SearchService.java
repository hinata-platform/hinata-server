package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleAccess;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.BoardLinks;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchResponse.SearchGroup;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 */
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

	private static final String F_TITLE = "title";
	private static final String F_NAME = "name";
	private static final String F_TAGS = "tags";
	private static final String F_UPDATED = "updatedAt";

	private final MongoTemplate mongo;
	private final UserRepository users;
	private final ProjectRepository projects;
	private final ProjectService projectService;
	private final IssueRepository issues;
	private final ArticleAccess articleAccess;

	/** What one caller may find, worked out once per search. */
	private record Reach(Set<String> activeProjects, Set<String> archivedProjects,
			Criteria pages) {

		Criteria issues(boolean archived) {
			return new Criteria().andOperator(archivedIs(archived),
					Criteria.where("projectId").in(activeProjects));
		}

		Criteria projects(boolean archived) {
			return new Criteria().andOperator(Criteria.where("archived").is(archived),
					Criteria.where("_id").in(archived ? archivedProjects : activeProjects));
		}

		Criteria boards() {
			return Criteria.where("projectIds").in(activeProjects);
		}
	}

	/**
	 * The caller's reach, worked out once per search: active projects always, the
	 * archived ones only for an archive search, and the pages from the same project
	 * set rather than a second lookup of it.
	 */
	private Reach reachOf(User user, boolean archivedSearch) {
		Set<String> active = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> archived = archivedSearch ? projectService.archivedVisibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet()) : Set.of();
		return new Reach(active, archived, articleAccess.sightOf(user, active).criteria());
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
		Reach reach = reachOf(user, archived);
		String q = rawQuery == null ? "" : rawQuery.trim();
		SearchCategory only = SearchCategory.parse(scope);
		int cap = only == null ? CAP_ALL : CAP_SCOPED;

		// Empty query + a specific entity scope → suggest the latest entities.
		// Empty + "all" (or Commands) → no groups (the client shows recents) —
		// except in archive mode, where the bare keyword should already reveal
		// the latest archived items.
		final List<SearchGroup> groups;
		if (!q.isBlank()) {
			groups = queryGroups(reach, q, only, cap, archived);
		} else if (only != null || archived) {
			groups = suggestGroups(reach, only, archived);
		} else {
			groups = List.of();
		}
		// The counts do not depend on what is typed: they are worked out when the
		// palette opens (an empty query) and not again on every keystroke after it.
		return new SearchResponse(groups, q.isBlank() ? counts(reach) : java.util.Map.of());
	}

	private List<SearchGroup> queryGroups(Reach reach, String q, SearchCategory only, int cap,
			boolean archived) {
		List<SearchGroup> groups = new ArrayList<>();
		for (SearchCategory cat : SearchCategory.values()) {
			if (only != null && cat != only) continue;
			List<SearchHit> hits = switch (cat) {
				case ISSUES -> mapIssues(searchIssues(reach, q, cap, archived), archived);
				case PROJECTS -> mapProjects(searchProjects(reach, q, cap, archived), archived);
				// People, boards and docs have no archive — hidden in archive mode.
				case PEOPLE -> archived ? List.<SearchHit>of() : mapPeople(searchPeople(q, cap));
				case BOARDS -> archived ? List.<SearchHit>of() : searchBoards(reach, q, cap);
				case DOCS -> archived ? List.<SearchHit>of() : mapDocs(searchDocs(reach, q, cap));
			};
			if (!hits.isEmpty()) groups.add(new SearchGroup(cat.name(), hits));
		}
		return groups;
	}

	/** Empty-query suggestions: latest entities of one scope, or — in archive
	 * mode — the latest archived issues and projects across both categories. */
	private List<SearchGroup> suggestGroups(Reach reach, SearchCategory only, boolean archived) {
		if (!archived) return groupOf(only, suggest(reach, only));
		List<SearchGroup> groups = new ArrayList<>();
		for (SearchCategory cat : List.of(SearchCategory.ISSUES, SearchCategory.PROJECTS)) {
			if (only != null && cat != only) continue;
			List<SearchHit> hits = switch (cat) {
				case ISSUES -> mapIssues(
						latest(Issue.class, SUGGEST_LIMIT, F_UPDATED, reach.issues(true)), true);
				case PROJECTS -> mapProjects(
						latest(Project.class, SUGGEST_LIMIT, F_UPDATED, reach.projects(true)), true);
				default -> List.<SearchHit>of();
			};
			if (!hits.isEmpty()) groups.add(new SearchGroup(cat.name(), hits));
		}
		return groups;
	}

	private static List<SearchGroup> groupOf(SearchCategory cat, List<SearchHit> hits) {
		return hits.isEmpty() ? List.of() : List.of(new SearchGroup(cat.name(), hits));
	}

	/** Latest entities of [cat] (most-recent first) for the empty-query state. */
	private List<SearchHit> suggest(Reach reach, SearchCategory cat) {
		return switch (cat) {
			case ISSUES -> mapIssues(
					latest(Issue.class, SUGGEST_LIMIT, F_UPDATED, reach.issues(false)), false);
			case PROJECTS -> mapProjects(
					latest(Project.class, SUGGEST_LIMIT, F_UPDATED, reach.projects(false)), false);
			case PEOPLE -> mapPeople(
					latest(User.class, SUGGEST_LIMIT, F_UPDATED, Criteria.where("active").is(true)));
			case BOARDS -> suggestBoards(reach);
			case DOCS -> mapDocs(
					latest(Article.class, SUGGEST_LIMIT, F_UPDATED, reach.pages()));
		};
	}

	// ─────────────────────────── per-category ─────────────────────────────

	private List<Issue> searchIssues(Reach reach, String q, int cap, boolean archived) {
		return hybrid(Issue.class, q, cap,
				List.of(contains(F_TITLE, q), prefix("readableId", q), contains(F_TAGS, q)),
				reach.issues(archived), F_UPDATED, Issue::getId, Issue::getTitle);
	}

	// Archived projects are hidden platform-wide; the reach holds active projects
	// only, so their issues and boards never reach the mapping below.
	private List<SearchHit> mapIssues(List<Issue> hits, boolean archived) {
		Map<String, User> byId = userMap(hits.stream()
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

	private List<Project> searchProjects(Reach reach, String q, int cap, boolean archived) {
		return hybrid(Project.class, q, cap,
				List.of(contains(F_NAME, q), prefix("key", q)),
				reach.projects(archived),
				F_UPDATED, Project::getId, Project::getName);
	}

	private List<SearchHit> mapProjects(List<Project> rawHits, boolean archived) {
		List<Project> hits = rawHits.stream().filter(p -> p.isArchived() == archived).toList();
		Map<String, User> byId = userMap(hits.stream()
				.flatMap(p -> p.getMemberIds().stream()).collect(Collectors.toSet()));

		return hits.stream().map(p -> {
			long total = issues.countByProjectId(p.getId());
			long done = p.getResolvedStates().isEmpty()
					? 0
					: issues.countByProjectIdAndStateIn(p.getId(), p.getResolvedStates());
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

	private List<User> searchPeople(String q, int cap) {
		return hybrid(User.class, q, cap,
				List.of(contains("displayName", q), contains("username", q), contains(F_TITLE, q)),
				Criteria.where("active").is(true), null, User::getId, User::getDisplayName);
	}

	private List<SearchHit> mapPeople(List<User> hits) {
		return hits.stream()
				.filter(User::isActive)
				.map(u -> SearchHit.builder()
						.category(SearchCategory.PEOPLE.name())
						.id(u.getId())
						.route("/admin/users")
						.title(u.getDisplayName())
						.subtitle(u.getTitle())
						.avatarUrl(u.getAvatarUrl())
						.build())
				.toList();
	}

	private List<SearchHit> searchBoards(Reach reach, String q, int cap) {
		List<AgileBoard> boards = hybrid(AgileBoard.class, q, cap,
				List.of(contains(F_NAME, q)), reach.boards(), null, AgileBoard::getId, AgileBoard::getName);
		List<Sprint> sprints = hybrid(Sprint.class, q, cap,
				List.of(contains(F_NAME, q), contains("goal", q)), sprintsOf(reach), null,
				Sprint::getId, Sprint::getName);
		return combineBoards(boards, sprints, cap);
	}

	private List<SearchHit> suggestBoards(Reach reach) {
		List<AgileBoard> boards = latest(AgileBoard.class, SUGGEST_LIMIT, "createdAt", reach.boards());
		int remaining = SUGGEST_LIMIT - boards.size();
		List<Sprint> sprints = remaining > 0
				? latest(Sprint.class, remaining, "createdAt", sprintsOf(reach))
				: List.of();
		return combineBoards(boards, sprints, SUGGEST_LIMIT);
	}

	/** Sprints of the boards the caller reaches — a sprint has no project of its own. */
	private Criteria sprintsOf(Reach reach) {
		return Criteria.where("boardId").in(boardIds(reach));
	}

	private Set<String> boardIds(Reach reach) {
		Query query = Query.query(reach.boards());
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
				.route(BoardLinks.of(s.getBoardId()))
				.title(s.getName())
				.subtitle(s.getGoal() != null && !s.getGoal().isBlank() ? s.getGoal() : "Sprint")
				.build();
	}

	private List<Article> searchDocs(Reach reach, String q, int cap) {
		return hybrid(Article.class, q, cap,
				List.of(contains(F_TITLE, q), contains(F_TAGS, q)),
				reach.pages(), F_UPDATED, Article::getId, Article::getTitle);
	}

	private List<SearchHit> mapDocs(List<Article> hits) {
		Map<String, Project> byId = projectMap(hits.stream()
				.map(Article::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet()));

		return hits.stream().map(a -> SearchHit.builder()
				.category(SearchCategory.DOCS.name())
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
	private <T> List<T> hybrid(Class<T> type, String q, int cap, List<Criteria> regexOrs,
			Criteria filter, String sortField, Function<T, String> idFn,
			Function<T, String> labelFn) {
		int candidates = cap * CANDIDATE_FACTOR;

		Criteria anyLabel = new Criteria().orOperator(regexOrs.toArray(Criteria[]::new));
		// One criteria object: the reach filter is itself an $and/$or, and a query holds
		// only one key-less criteria next to the label $or.
		Query regexQuery = new Query(filter == null ? anyLabel : new Criteria().andOperator(anyLabel, filter))
				.limit(candidates);
		if (sortField != null) regexQuery.with(Sort.by(Sort.Direction.DESC, sortField));
		List<T> regexHits = mongo.find(regexQuery, type);

		List<T> textHits = List.of();
		if (q.length() >= 2) {
			try {
				TextCriteria tc = TextCriteria.forDefaultLanguage().matchingAny(q.split("\\s+"));
				TextQuery textQuery = TextQuery.queryText(tc).sortByScore();
				if (filter != null) textQuery.addCriteria(filter);
				textQuery.limit(candidates);
				textHits = mongo.find(textQuery, type);
			} catch (RuntimeException ignored) {
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

	/** Counts within the caller's reach; a total over everybody's data would say
	 * how much there is that they cannot see. */
	private Map<String, Long> counts(Reach reach) {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(SearchCategory.ISSUES.name(), mongo.count(Query.query(reach.issues(false)), Issue.class));
		counts.put(SearchCategory.PROJECTS.name(), (long) reach.activeProjects().size());
		counts.put(SearchCategory.PEOPLE.name(), mongo.count(Query.query(Criteria.where("active").is(true)), User.class));
		Set<String> boards = boardIds(reach);
		counts.put(SearchCategory.BOARDS.name(), boards.size()
				+ mongo.count(Query.query(Criteria.where("boardId").in(boards)), Sprint.class));
		counts.put(SearchCategory.DOCS.name(), mongo.count(Query.query(reach.pages()), Article.class));
		return counts;
	}

	// ─────────────────────────── helpers ──────────────────────────────────

	/** Case-insensitive "contains" — regex-escaped (NoSQL-injection safe). */
	private static Criteria contains(String field, String q) {
		return Criteria.where(field).regex(Pattern.quote(q), "i");
	}

	/** Case-insensitive anchored prefix — index-friendly for ids/keys. */
	private static Criteria prefix(String field, String q) {
		return Criteria.where(field).regex("^" + Pattern.quote(q), "i");
	}

	/** Issue archived filter — {@code ne(true)} keeps matching pre-migration
	 * documents that lack the flag. */
	private static Criteria archivedIs(boolean archived) {
		return archived
				? Criteria.where("archived").is(true)
				: Criteria.where("archived").ne(true);
	}

	/** The most-recent [limit] entities by [sortField] (optional [filter]). */
	private <T> List<T> latest(Class<T> type, int limit, String sortField, Criteria filter) {
		Query query = filter != null ? new Query(filter) : new Query();
		query.with(Sort.by(Sort.Direction.DESC, sortField)).limit(limit);
		return mongo.find(query, type);
	}

	private Map<String, User> userMap(Collection<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, User> map = new HashMap<>();
		users.findAllById(ids).forEach(u -> map.put(u.getId(), u));
		return map;
	}

	private Map<String, Project> projectMap(Set<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, Project> map = new HashMap<>();
		projects.findAllById(ids).forEach(p -> map.put(p.getId(), p));
		return map;
	}
}
