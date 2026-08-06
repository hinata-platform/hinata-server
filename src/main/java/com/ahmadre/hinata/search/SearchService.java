package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleVisibility;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchResponse.SearchGroup;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
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
	private final IssueRepository issues;
	private final ProjectService projectService;
	private final TeamService teamService;
	private final FrozenContentService frozen;

	/**
	 * What one viewer is allowed to see, resolved once per search.
	 *
	 * <p>Resolved once and threaded through rather than asked per category, because
	 * the membership lookup is several queries and there are five categories. More
	 * importantly, one object means one answer: a category that forgot to ask would
	 * previously just have searched everything, which is exactly how this went wrong.
	 *
	 * <p>{@code admin} short-circuits every filter instead of expanding to "every id
	 * in the organisation". That is the same answer computed cheaply, and it keeps an
	 * {@code $in} clause from growing with the install.
	 */
	private record Scope(boolean admin, Set<String> projectIds, Set<String> archivedProjectIds,
			Set<String> teamIds) {

		/** Restricts [field] to the projects this viewer reaches; null for an admin. */
		Criteria projects(String field) {
			return admin ? null : Criteria.where(field).in(projectIds);
		}
	}

	private Scope scopeOf(User viewer) {
		if (viewer.isAdmin()) {
			return new Scope(true, Set.of(), Set.of(), Set.of());
		}
		return new Scope(false,
				projectService.visibleTo(viewer).stream()
						.map(Project::getId).collect(Collectors.toSet()),
				projectService.archivedVisibleTo(viewer).stream()
						.map(Project::getId).collect(Collectors.toSet()),
				teamService.visibleTo(viewer).stream()
						.map(Team::getId).collect(Collectors.toSet()));
	}

	public SearchResponse search(String rawQuery, String scope, User viewer) {
		return search(rawQuery, scope, false, viewer);
	}

	/**
	 * @param archived search the archive instead: archived (soft-deleted)
	 *                 issues and archived projects, badged by the client. Only
	 *                 those two categories exist in the archive; the rest stay
	 *                 empty. An empty query suggests the latest archived items.
	 * @param viewer   whose access decides what may be returned. Every category is
	 *                 filtered by it <em>inside</em> the Mongo query rather than
	 *                 afterwards — sifting a capped candidate window would hand back
	 *                 a short page, and an empty one to anyone whose own work happens
	 *                 to rank below other people's.
	 */
	public SearchResponse search(String rawQuery, String scope, boolean archived, User viewer) {
		String q = rawQuery == null ? "" : rawQuery.trim();
		SearchCategory only = SearchCategory.parse(scope);
		int cap = only == null ? CAP_ALL : CAP_SCOPED;
		Scope reach = scopeOf(viewer);

		// Empty query + a specific entity scope → suggest the latest entities.
		// Empty + "all" (or Commands) → no groups (the client shows recents) —
		// except in archive mode, where the bare keyword should already reveal
		// the latest archived items.
		final List<SearchGroup> groups;
		if (!q.isBlank()) {
			groups = queryGroups(q, only, cap, archived, reach);
		} else if (only != null || archived) {
			groups = suggestGroups(only, archived, reach);
		} else {
			groups = List.of();
		}
		return new SearchResponse(groups, counts(reach));
	}

	private List<SearchGroup> queryGroups(String q, SearchCategory only, int cap,
			boolean archived, Scope reach) {
		List<SearchGroup> groups = new ArrayList<>();
		for (SearchCategory cat : SearchCategory.values()) {
			if (only != null && cat != only) continue;
			List<SearchHit> hits = switch (cat) {
				case ISSUES -> mapIssues(searchIssues(q, cap, archived, reach), archived);
				case PROJECTS -> mapProjects(searchProjects(q, cap, archived, reach), archived);
				// People, boards and docs have no archive — hidden in archive mode.
				case PEOPLE -> archived ? List.<SearchHit>of() : mapPeople(searchPeople(q, cap));
				case BOARDS -> archived ? List.<SearchHit>of() : searchBoards(q, cap, reach);
				case DOCS -> archived ? List.<SearchHit>of() : mapDocs(searchDocs(q, cap, reach));
			};
			if (!hits.isEmpty()) groups.add(new SearchGroup(cat.name(), hits));
		}
		return groups;
	}

	/** Empty-query suggestions: latest entities of one scope, or — in archive
	 * mode — the latest archived issues and projects across both categories. */
	private List<SearchGroup> suggestGroups(SearchCategory only, boolean archived, Scope reach) {
		if (!archived) return groupOf(only, suggest(only, reach));
		List<SearchGroup> groups = new ArrayList<>();
		for (SearchCategory cat : List.of(SearchCategory.ISSUES, SearchCategory.PROJECTS)) {
			if (only != null && cat != only) continue;
			List<SearchHit> hits = switch (cat) {
				case ISSUES -> mapIssues(latest(Issue.class, SUGGEST_LIMIT, F_UPDATED,
						and(archivedIs(true), unfrozen(reach.projects("projectId"), FrozenTargetType.ISSUE))), true);
				case PROJECTS -> mapProjects(latest(Project.class, SUGGEST_LIMIT, F_UPDATED,
						and(archivedTrue(), visibleArchivedProjects(reach))), true);
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
	private List<SearchHit> suggest(SearchCategory cat, Scope reach) {
		return switch (cat) {
			case ISSUES -> mapIssues(latest(Issue.class, SUGGEST_LIMIT, F_UPDATED,
					and(archivedIs(false), unfrozen(reach.projects("projectId"), FrozenTargetType.ISSUE))), false);
			case PROJECTS -> mapProjects(latest(Project.class, SUGGEST_LIMIT, F_UPDATED,
					and(archivedFalse(), visibleProjects(reach))), false);
			case PEOPLE -> mapPeople(latest(User.class, SUGGEST_LIMIT, F_UPDATED,
					unfrozen(Criteria.where("active").is(true), FrozenTargetType.USER)));
			case BOARDS -> suggestBoards(reach);
			case DOCS -> mapDocs(latest(Article.class, SUGGEST_LIMIT, F_UPDATED,
					unfrozen(ArticleVisibility.criteria(reach.admin(), reach.projectIds(),
							reach.teamIds()), FrozenTargetType.ARTICLE)));
		};
	}

	// ─────────────────────────── per-category ─────────────────────────────

	private List<Issue> searchIssues(String q, int cap, boolean archived, Scope reach) {
		return hybrid(Issue.class, q, cap,
				List.of(contains(F_TITLE, q), prefix("readableId", q), contains(F_TAGS, q)),
				and(archivedIs(archived), unfrozen(reach.projects("projectId"), FrozenTargetType.ISSUE)),
				F_UPDATED, Issue::getId, Issue::getTitle);
	}

	/** Ids of active (non-archived) projects — archived projects are hidden
	 * platform-wide, so their issues/projects/boards never appear in search. */
	private Set<String> activeProjectIds() {
		return projects.findByArchivedFalse().stream()
				.map(Project::getId).collect(Collectors.toSet());
	}

	/**
	 * Hides issues whose project is archived.
	 *
	 * <p>Access is <em>not</em> what this does — that is already settled in the query
	 * by {@link Scope#projects}. This is the archive rule, and it stays a
	 * post-filter because an issue's own {@code archived} flag and its project's are
	 * independent: an active issue in an archived project must not surface, and there
	 * is no field on the issue that says so.
	 */
	private List<SearchHit> mapIssues(List<Issue> rawHits, boolean archived) {
		Set<String> active = activeProjectIds();
		List<Issue> hits = rawHits.stream()
				.filter(it -> active.contains(it.getProjectId())).toList();
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

	private List<Project> searchProjects(String q, int cap, boolean archived, Scope reach) {
		return hybrid(Project.class, q, cap,
				List.of(contains(F_NAME, q), prefix("key", q)),
				and(Criteria.where("archived").is(archived),
						archived ? visibleArchivedProjects(reach) : visibleProjects(reach)),
				F_UPDATED, Project::getId, Project::getName);
	}

	/** Restricts a project query to the ones this viewer reaches; null for an admin. */
	private static Criteria visibleProjects(Scope reach) {
		return reach.admin() ? null : Criteria.where("_id").in(reach.projectIds());
	}

	private static Criteria visibleArchivedProjects(Scope reach) {
		return reach.admin() ? null : Criteria.where("_id").in(reach.archivedProjectIds());
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

	/**
	 * People have no access filter — the directory is visible to every member — but
	 * they do have a freeze filter, which is why the {@code null} that used to sit in
	 * the {@code filter} slot is now an exclusion. A frozen account's display name
	 * and title are user-written text on a moderated surface
	 * ({@code ModerationSurface.PROFILE}), and the avatar this hit carries is served
	 * over the one unauthenticated content route in the product.
	 */
	private List<User> searchPeople(String q, int cap) {
		return hybrid(User.class, q, cap,
				List.of(contains("displayName", q), contains("username", q), contains(F_TITLE, q)),
				frozen.exclusion("_id", FrozenTargetType.USER),
				null, User::getId, User::getDisplayName);
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

	private List<SearchHit> searchBoards(String q, int cap, Scope reach) {
		List<AgileBoard> boards = hybrid(AgileBoard.class, q, cap,
				List.of(contains(F_NAME, q)), reach.projects("projectIds"),
				null, AgileBoard::getId, AgileBoard::getName);
		List<Sprint> sprints = hybrid(Sprint.class, q, cap,
				List.of(contains(F_NAME, q), contains("goal", q)), sprintsOfVisibleBoards(reach),
				null, Sprint::getId, Sprint::getName);
		return combineBoards(boards, sprints, cap);
	}

	private List<SearchHit> suggestBoards(Scope reach) {
		List<AgileBoard> boards = latest(AgileBoard.class, SUGGEST_LIMIT, "createdAt",
				reach.projects("projectIds"));
		int remaining = SUGGEST_LIMIT - boards.size();
		List<Sprint> sprints = remaining > 0
				? latest(Sprint.class, remaining, "createdAt", sprintsOfVisibleBoards(reach))
				: List.of();
		return combineBoards(boards, sprints, SUGGEST_LIMIT);
	}

	/**
	 * Restricts sprints to boards this viewer reaches; null for an admin.
	 *
	 * <p>A sprint carries only a {@code boardId}, so its access has to be resolved
	 * through the board — which is why it was the one category with no filter at all
	 * and leaked every sprint name and goal in the organisation. The extra query is
	 * the price of that indirection and is paid once per search, not per hit.
	 */
	private Criteria sprintsOfVisibleBoards(Scope reach) {
		if (reach.admin()) {
			return null;
		}
		Query boardIds = new Query(Criteria.where("projectIds").in(reach.projectIds()));
		boardIds.fields().include("_id");
		Set<String> ids = mongo.find(boardIds, AgileBoard.class).stream()
				.map(AgileBoard::getId).collect(Collectors.toSet());
		return Criteria.where("boardId").in(ids);
	}

	private List<SearchHit> combineBoards(List<AgileBoard> boards, List<Sprint> sprints, int cap) {
		Set<String> active = activeProjectIds();
		List<SearchHit> out = new ArrayList<>();
		boards.stream()
				.filter(b -> b.getProjectIds().stream().anyMatch(active::contains))
				.forEach(b -> out.add(mapBoard(b)));
		sprints.forEach(s -> out.add(mapSprint(s)));
		return out.size() > cap ? out.subList(0, cap) : out;
	}

	private SearchHit mapBoard(AgileBoard b) {
		return SearchHit.builder()
				.category(SearchCategory.BOARDS.name())
				.id(b.getId())
				.route("/board")
				.title(b.getName())
				.subtitle("Agile board")
				.build();
	}

	private SearchHit mapSprint(Sprint s) {
		return SearchHit.builder()
				.category(SearchCategory.BOARDS.name())
				.id(s.getId())
				.route("/board")
				.title(s.getName())
				.subtitle(s.getGoal() != null && !s.getGoal().isBlank() ? s.getGoal() : "Sprint")
				.build();
	}

	private List<Article> searchDocs(String q, int cap, Scope reach) {
		return hybrid(Article.class, q, cap,
				List.of(contains(F_TITLE, q), contains(F_TAGS, q)),
				unfrozen(ArticleVisibility.criteria(reach.admin(), reach.projectIds(),
						reach.teamIds()), FrozenTargetType.ARTICLE),
				F_UPDATED, Article::getId, Article::getTitle);
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
	 * Runs the regex + $text pair, merges (regex first, then text), dedupes by
	 * {@code idFn}, floats prefix matches on {@code labelFn} to the top and caps.
	 */
	private <T> List<T> hybrid(Class<T> type, String q, int cap, List<Criteria> regexOrs,
			String sortField, Function<T, String> idFn, Function<T, String> labelFn) {
		return hybrid(type, q, cap, regexOrs, null, sortField, idFn, labelFn);
	}

	/** Variant with an extra AND [filter] (e.g. the archived flag) applied to
	 * both the regex and the $text query. */
	private <T> List<T> hybrid(Class<T> type, String q, int cap, List<Criteria> regexOrs,
			Criteria filter, String sortField, Function<T, String> idFn,
			Function<T, String> labelFn) {
		int candidates = cap * CANDIDATE_FACTOR;

		// Composed into one root criteria rather than added on top. Both halves are
		// key-less ($or of the label regexes, $and of archive + access), and Spring
		// Data refuses to hold two key-less criteria in one Query — addCriteria here
		// throws InvalidMongoDbApiUsageException instead of narrowing anything.
		Criteria regexRoot = new Criteria().orOperator(regexOrs.toArray(Criteria[]::new));
		Query regexQuery = new Query(
				filter == null ? regexRoot : new Criteria().andOperator(regexRoot, filter))
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

	/**
	 * The per-category totals behind the palette's chips.
	 *
	 * <p>Scoped like everything else. An unscoped {@code estimatedCount} is a small
	 * leak on its own — it tells anyone with an account how many issues and documents
	 * the organisation holds — and a confusing one on top of that: a chip reading
	 * "1,284 issues" above three results invites a bug report about search being
	 * broken. Admins keep the estimate because it is free and, for them, accurate.
	 */
	private Map<String, Long> counts(Scope reach) {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(SearchCategory.ISSUES.name(),
				count(Issue.class, unfrozen(reach.projects("projectId"), FrozenTargetType.ISSUE)));
		counts.put(SearchCategory.PROJECTS.name(),
				count(Project.class, visibleProjects(reach)));
		// People are deliberately unscoped by *access*: the directory is already
		// readable by any signed-in account through GET /api/v1/users, because mention
		// and assignee pickers need it. Narrowing it there would only make search
		// disagree with the rest of the product. Freeze is a different question and
		// does apply, so this is no longer the raw estimate — a chip counting an
		// account whose group cannot show it is the badge-versus-page mismatch the
		// comment counts already had to be corrected for.
		counts.put(SearchCategory.PEOPLE.name(),
				count(User.class, unfrozen(Criteria.where("active").is(true),
						FrozenTargetType.USER)));
		counts.put(SearchCategory.BOARDS.name(),
				count(AgileBoard.class, reach.projects("projectIds"))
						+ count(Sprint.class, sprintsOfVisibleBoards(reach)));
		counts.put(SearchCategory.DOCS.name(), count(Article.class,
				unfrozen(ArticleVisibility.criteria(reach.admin(), reach.projectIds(),
						reach.teamIds()), FrozenTargetType.ARTICLE)));
		return counts;
	}

	/** Counts [type], estimating when there is nothing to restrict (i.e. for an admin). */
	private <T> long count(Class<T> type, Criteria filter) {
		return filter == null
				? mongo.estimatedCount(type)
				: mongo.count(new Query(filter), type);
	}

	// ─────────────────────────── helpers ──────────────────────────────────

	/**
	 * Combines the archive filter with the access filter, dropping a null access
	 * filter (an admin, who is not restricted).
	 *
	 * <p>{@code andOperator} rather than chaining {@code Criteria.and(...)}: the two
	 * halves can name the same field — a project query restricts {@code _id} while
	 * the access half restricts {@code _id} too — and chaining would silently
	 * overwrite one of them instead of requiring both.
	 */
	private static Criteria and(Criteria base, Criteria access) {
		return access == null ? base : new Criteria().andOperator(base, access);
	}

	/**
	 * The access filter for a category, with the freeze exclusion composed onto it.
	 *
	 * <p>Composed <em>alongside</em> access rather than folded into it, and that is
	 * the whole reason freeze is a separate collaborator. The access filters answer
	 * {@code null} for a platform admin — meaning "no restriction" — and
	 * {@link #and} drops a null access filter entirely. A freeze that travelled
	 * inside that value would evaporate for precisely the account it most has to
	 * apply to, because an admin does not bypass a freeze. So
	 * {@link FrozenContentService#exclusion} never returns null, and this method is
	 * the one place the two are joined.
	 *
	 * <p>When nothing is frozen the exclusion is {@code $nin: []}, which matches
	 * every document — so an install with no incident runs the query it always ran,
	 * one {@code $and} wrapper deeper.
	 */
	private Criteria unfrozen(Criteria access, FrozenTargetType type) {
		Criteria exclusion = frozen.exclusion("_id", type);
		return access == null ? exclusion : new Criteria().andOperator(access, exclusion);
	}

	/** Case-insensitive "contains" — regex-escaped (NoSQL-injection safe). */
	private static Criteria contains(String field, String q) {
		return Criteria.where(field).regex(Pattern.quote(q), "i");
	}

	/** Case-insensitive anchored prefix — index-friendly for ids/keys. */
	private static Criteria prefix(String field, String q) {
		return Criteria.where(field).regex("^" + Pattern.quote(q), "i");
	}

	private static Criteria archivedFalse() {
		return Criteria.where("archived").is(false);
	}

	private static Criteria archivedTrue() {
		return Criteria.where("archived").is(true);
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
