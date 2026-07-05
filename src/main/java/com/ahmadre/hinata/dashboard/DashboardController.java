package com.ahmadre.hinata.dashboard;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.git.GitDevInfo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Aggregated data behind the main dashboard (Today Task, completion, ranking, tracker). */
@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

	private final ProjectService projects;
	private final UserRepository users;
	private final MongoTemplate mongo;
	private final CurrentUser currentUser;
	private final TeamService teamService;
	private final DashboardPrefsRepository prefsRepo;

	public record ProjectCompletion(long done, long inProgress, long backlog, long total) {
	}

	/** A board the caller can pin to the dashboard hero (for the picker). */
	public record BoardOption(String id, String name, String type) {
	}

	/** The caller's saved dashboard personalisation, echoed back for the UI. */
	public record DashboardPrefsDto(String boardId, List<String> projectIds,
			List<String> teamIds, List<String> hiddenCards) {
	}

	public record RankEntry(String userId, String displayName, String title, String avatarUrl,
			long points) {
	}

	public record TrackerDay(LocalDate date, int focusMinutes) {
	}

	/** One ISO calendar week of tracked focus time (for the "Month" range). */
	public record TrackerWeek(int week, int focusMinutes) {
	}

	public record SprintMember(String userId, String displayName, String avatarUrl) {
	}

	/**
	 * Snapshot of the caller's active board for the dashboard hero.
	 * {@code kind} is {@code SPRINT} (a running sprint on a SCRUM board) or
	 * {@code KANBAN} (any board without an active sprint). Sprint-only fields
	 * ({@code day}/{@code days}/{@code points}/{@code pointsTotal}) are {@code 0}
	 * for Kanban; progress there is driven by issue completion. {@code null} only
	 * when the caller has no board at all.
	 */
	public record BoardSummary(String kind, String boardId, String name, String goal,
			int day, int days, int points, int pointsTotal, int issuesDone, int issuesTotal,
			List<SprintMember> members) {
	}

	/**
	 * A recent development event flattened across the caller's repos.
	 * {@code kind} is one of {@code commit | pr | deploy | merge}.
	 */
	public record GitEvent(String kind, String ref, String text, String repo, String authorName,
			Instant at, String issueKey) {
	}

	public record DashboardData(List<Issue> todayTasks, ProjectCompletion completion,
			List<RankEntry> ranking, List<TrackerDay> tracker, List<TrackerWeek> trackerMonth,
			BoardSummary activeBoard, List<GitEvent> gitActivity, List<BoardOption> boards,
			DashboardPrefsDto prefs) {
	}

	/**
	 * Aggregated dashboard. Query params (sent for live preview while the user is
	 * in edit mode) override the saved personalisation; when absent, the caller's
	 * persisted {@link DashboardPrefs} drive the scope and pinned hero board.
	 *
	 * @param projectIds scope for aggregated data; empty ⇒ all visible projects
	 * @param teamIds    scope for the ranking; empty ⇒ all teams
	 * @param boardId    pinned hero board; blank ⇒ auto-pick
	 */
	@GetMapping
	public DashboardData dashboard(
			@RequestParam(required = false) String boardId,
			@RequestParam(required = false) List<String> projectIds,
			@RequestParam(required = false) List<String> teamIds) {
		User user = currentUser.require();
		DashboardPrefs saved = prefsRepo.findById(user.getId()).orElse(null);
		boolean preview = boardId != null || projectIds != null || teamIds != null;

		String effBoardId = blankToNull(preview ? boardId : (saved != null ? saved.getBoardId() : null));
		List<String> reqProjects = preview ? projectIds : (saved != null ? saved.getProjectIds() : null);
		List<String> reqTeams = preview ? teamIds : (saved != null ? saved.getTeamIds() : null);

		List<Project> allVisible = projects.visibleTo(user);
		List<Project> visible = scopeProjects(allVisible, reqProjects);
		List<String> scopedIds = visible.stream().map(Project::getId).toList();
		Set<String> rankingUsers = teamMemberIds(user, reqTeams);

		return new DashboardData(
				todayTasks(user, scopedIds),
				completion(visible, scopedIds),
				ranking(scopedIds, rankingUsers),
				tracker(user),
				trackerMonth(user),
				activeBoard(visible, scopedIds, effBoardId),
				gitActivity(scopedIds),
				boardOptions(allVisible),
				toDto(saved));
	}

	/** Persist the caller's dashboard personalisation. */
	@PutMapping("/prefs")
	public DashboardPrefsDto savePrefs(@RequestBody DashboardPrefsDto body) {
		User user = currentUser.require();
		DashboardPrefs prefs = DashboardPrefs.builder()
				.userId(user.getId())
				.boardId(blankToNull(body.boardId()))
				.projectIds(new ArrayList<>(body.projectIds() != null ? body.projectIds() : List.of()))
				.teamIds(new ArrayList<>(body.teamIds() != null ? body.teamIds() : List.of()))
				.hiddenCards(new ArrayList<>(body.hiddenCards() != null ? body.hiddenCards() : List.of()))
				.build();
		return toDto(prefsRepo.save(prefs));
	}

	private static DashboardPrefsDto toDto(DashboardPrefs p) {
		if (p == null) {
			return new DashboardPrefsDto(null, List.of(), List.of(), List.of());
		}
		return new DashboardPrefsDto(p.getBoardId(), p.getProjectIds(), p.getTeamIds(),
				p.getHiddenCards());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	/** Narrow [all] visible projects to [requested] (by id); empty/null ⇒ all. */
	private static List<Project> scopeProjects(List<Project> all, List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return all;
		}
		Set<String> wanted = Set.copyOf(requested);
		List<Project> scoped = all.stream().filter(p -> wanted.contains(p.getId())).toList();
		return scoped.isEmpty() ? all : scoped;
	}

	/**
	 * The union of member ids across the requested (and caller-visible) teams, or
	 * {@code null} when no team filter is requested (ranking spans everyone).
	 */
	private Set<String> teamMemberIds(User user, List<String> teamIds) {
		if (teamIds == null || teamIds.isEmpty()) {
			return null;
		}
		Set<String> visibleTeamIds = teamService.visibleTo(user).stream()
				.map(Team::getId).collect(Collectors.toSet());
		Set<String> wanted = teamIds.stream().filter(visibleTeamIds::contains)
				.collect(Collectors.toSet());
		if (wanted.isEmpty()) {
			return Set.of();
		}
		return teamService.visibleTo(user).stream()
				.filter(t -> wanted.contains(t.getId()))
				.flatMap(t -> t.getMembers().stream())
				.map(TeamMembership::getUserId)
				.collect(Collectors.toSet());
	}

	/** All boards across the caller's visible projects, for the hero picker. */
	private List<BoardOption> boardOptions(List<Project> visible) {
		List<String> projectIds = visible.stream().map(Project::getId).toList();
		if (projectIds.isEmpty()) {
			return List.of();
		}
		return mongo.find(Query.query(Criteria.where("projectIds").in(projectIds)), AgileBoard.class)
				.stream()
				.map(b -> new BoardOption(b.getId(), b.getName(), b.getType().name()))
				.toList();
	}

	private List<Issue> todayTasks(User user, List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		LocalDate today = LocalDate.now();
		// "Assigned to me" matches primary or secondary assignee (legacy + new docs).
		Criteria assignedToMe = new Criteria().orOperator(
				Criteria.where("assigneeIds").is(user.getId()),
				Criteria.where("assigneeId").is(user.getId()));
		Criteria urgent = new Criteria().orOperator(
				Criteria.where("dueDate").lte(today),
				Criteria.where("priority").in("SHOWSTOPPER", "CRITICAL", "MAJOR"));
		Query query = Query.query(new Criteria().andOperator(
				assignedToMe,
				Criteria.where("projectId").in(projectIds),
				Criteria.where("resolvedAt").is(null),
				urgent));
		query.limit(12);
		return mongo.find(query, Issue.class).stream()
				.sorted(Comparator.comparing(Issue::getPriority))
				.toList();
	}

	private ProjectCompletion completion(List<Project> visible, List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return new ProjectCompletion(0, 0, 0, 0);
		}
		List<String> resolvedStates = visible.stream()
				.flatMap(p -> p.getResolvedStates().stream()).distinct().toList();
		long total = mongo.count(Query.query(Criteria.where("projectId").in(projectIds)), Issue.class);
		long done = mongo.count(Query.query(Criteria.where("projectId").in(projectIds)
				.and("state").in(resolvedStates)), Issue.class);
		long backlog = mongo.count(Query.query(Criteria.where("projectId").in(projectIds)
				.and("state").in("Backlog", "Open")), Issue.class);
		return new ProjectCompletion(done, Math.max(0, total - done - backlog), backlog, total);
	}

	/**
	 * Resolved issues in the last 30 days, scored per assignee. When
	 * {@code teamUsers} is non-null the ranking is restricted to those users
	 * (the union of the selected teams' members); {@code null} spans everyone.
	 */
	private List<RankEntry> ranking(List<String> projectIds, Set<String> teamUsers) {
		if (projectIds.isEmpty() || (teamUsers != null && teamUsers.isEmpty())) {
			return List.of();
		}
		Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
		List<Issue> resolved = mongo.find(Query.query(Criteria.where("projectId").in(projectIds)
				.and("resolvedAt").gte(since).and("assigneeId").ne(null)), Issue.class);
		Map<String, Long> points = resolved.stream()
				.filter(i -> teamUsers == null || teamUsers.contains(i.getAssigneeId()))
				.collect(Collectors.groupingBy(Issue::getAssigneeId, Collectors.counting()));
		List<RankEntry> entries = new ArrayList<>();
		points.forEach((userId, count) -> users.findById(userId).ifPresent(u -> entries.add(
				new RankEntry(u.getId(), u.getDisplayName(), u.getTitle(), u.getAvatarUrl(), count))));
		entries.sort(Comparator.comparingLong(RankEntry::points).reversed());
		return entries.size() > 10 ? entries.subList(0, 10) : entries;
	}

	private List<TrackerDay> tracker(User user) {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(6);
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("userId").is(user.getId())
				.and("date").gte(from).lte(today)), WorkItem.class);
		Map<LocalDate, Integer> perDay = new TreeMap<>();
		for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
			perDay.put(day, 0);
		}
		items.forEach(item -> perDay.merge(item.getDate(), item.getDurationMinutes(), Integer::sum));
		return perDay.entrySet().stream()
				.map(entry -> new TrackerDay(entry.getKey(), entry.getValue()))
				.toList();
	}

	/** Focus minutes bucketed into the last five ISO weeks (Mon-anchored). */
	private List<TrackerWeek> trackerMonth(User user) {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusWeeks(4).with(DayOfWeek.MONDAY);
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("userId").is(user.getId())
				.and("date").gte(from).lte(today)), WorkItem.class);
		Map<LocalDate, Integer> byWeek = new TreeMap<>();
		for (LocalDate week = from; !week.isAfter(today); week = week.plusWeeks(1)) {
			byWeek.put(week, 0);
		}
		items.forEach(item -> byWeek.merge(item.getDate().with(DayOfWeek.MONDAY),
				item.getDurationMinutes(), Integer::sum));
		WeekFields iso = WeekFields.ISO;
		return byWeek.entrySet().stream()
				.map(e -> new TrackerWeek(e.getKey().get(iso.weekOfWeekBasedYear()), e.getValue()))
				.toList();
	}

	/**
	 * The caller's active board for the hero: a running sprint when a SCRUM board
	 * has one, otherwise the first board as a Kanban overview (issue completion).
	 * Returns {@code null} only when the caller has no board at all.
	 */
	private BoardSummary activeBoard(List<Project> visible, List<String> projectIds,
			String pinnedBoardId) {
		if (projectIds.isEmpty()) {
			return null;
		}
		List<AgileBoard> boards = mongo.find(
				Query.query(Criteria.where("projectIds").in(projectIds)), AgileBoard.class);
		if (boards.isEmpty()) {
			return null;
		}
		Map<String, Set<String>> resolvedByProject = new HashMap<>();
		for (Project p : visible) {
			resolvedByProject.put(p.getId(), Set.copyOf(p.getResolvedStates()));
		}
		// A pinned board wins: show its sprint if one is running, else as Kanban.
		if (pinnedBoardId != null) {
			AgileBoard pinned = boards.stream()
					.filter(b -> pinnedBoardId.equals(b.getId())).findFirst().orElse(null);
			if (pinned != null) {
				if (pinned.getActiveSprintId() != null) {
					Sprint sprint = mongo.findById(pinned.getActiveSprintId(), Sprint.class);
					if (sprint != null) {
						return sprintBoard(pinned, sprint, resolvedByProject);
					}
				}
				return kanbanBoard(pinned, projectIds, resolvedByProject);
			}
		}
		// Prefer a running sprint; fall back to a Kanban overview of the first board.
		for (AgileBoard board : boards) {
			if (board.getActiveSprintId() == null) {
				continue;
			}
			Sprint sprint = mongo.findById(board.getActiveSprintId(), Sprint.class);
			if (sprint != null) {
				return sprintBoard(board, sprint, resolvedByProject);
			}
		}
		return kanbanBoard(boards.get(0), projectIds, resolvedByProject);
	}

	private BoardSummary sprintBoard(AgileBoard board, Sprint sprint,
			Map<String, Set<String>> resolvedByProject) {
		List<Issue> inSprint = mongo.find(
				Query.query(Criteria.where("sprintId").is(sprint.getId())), Issue.class);
		int pointsTotal = 0;
		int points = 0;
		int issuesDone = 0;
		LinkedHashSet<String> members = new LinkedHashSet<>();
		for (Issue issue : inSprint) {
			int pts = issue.getStoryPoints() != null ? issue.getStoryPoints() : 0;
			pointsTotal += pts;
			boolean done = resolvedByProject.getOrDefault(issue.getProjectId(), Set.of())
					.contains(issue.getState());
			if (done) {
				points += pts;
				issuesDone++;
			}
			if (issue.getAssigneeId() != null) {
				members.add(issue.getAssigneeId());
			}
		}
		int days = 0;
		int day = 0;
		if (sprint.getStartDate() != null && sprint.getEndDate() != null) {
			days = (int) Math.max(1, ChronoUnit.DAYS.between(sprint.getStartDate(), sprint.getEndDate()));
			long elapsed = ChronoUnit.DAYS.between(sprint.getStartDate(), LocalDate.now()) + 1;
			day = (int) Math.min(Math.max(elapsed, 1), days);
		}
		return new BoardSummary("SPRINT", board.getId(), sprint.getName(), sprint.getGoal(),
				day, days, points, pointsTotal, issuesDone, inSprint.size(), resolveMembers(members));
	}

	private BoardSummary kanbanBoard(AgileBoard board, List<String> visibleIds,
			Map<String, Set<String>> resolvedByProject) {
		List<String> boardProjects = board.getProjectIds().stream()
				.filter(visibleIds::contains).toList();
		if (boardProjects.isEmpty()) {
			boardProjects = visibleIds;
		}
		List<Issue> onBoard = mongo.find(
				Query.query(Criteria.where("projectId").in(boardProjects)), Issue.class);
		int issuesDone = 0;
		LinkedHashSet<String> members = new LinkedHashSet<>();
		for (Issue issue : onBoard) {
			if (resolvedByProject.getOrDefault(issue.getProjectId(), Set.of())
					.contains(issue.getState())) {
				issuesDone++;
			}
			if (issue.getAssigneeId() != null) {
				members.add(issue.getAssigneeId());
			}
		}
		return new BoardSummary("KANBAN", board.getId(), board.getName(), null,
				0, 0, 0, 0, issuesDone, onBoard.size(), resolveMembers(members));
	}

	private List<SprintMember> resolveMembers(LinkedHashSet<String> memberIds) {
		return memberIds.stream().limit(6)
				.map(id -> users.findById(id)
						.map(u -> new SprintMember(u.getId(), u.getDisplayName(), u.getAvatarUrl()))
						.orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	/** Recent commits, PRs, deploys and merges across the caller's repos, newest first. */
	private List<GitEvent> gitActivity(List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		List<GitDevInfo> infos = mongo.find(
				Query.query(Criteria.where("projectId").in(projectIds)), GitDevInfo.class);
		Map<String, String> nameCache = new HashMap<>();
		List<GitEvent> events = new ArrayList<>();
		for (GitDevInfo info : infos) {
			String key = info.getIssueKey();
			for (GitDevInfo.Commit c : info.getCommits()) {
				String sha = c.getSha();
				String ref = sha != null && sha.length() > 7 ? sha.substring(0, 7) : sha;
				events.add(new GitEvent("commit", ref, firstLine(c.getMessage()),
						shortRepo(c.getRepo()), authorName(c.getAuthorId(), nameCache), c.getAt(), key));
			}
			for (GitDevInfo.PullRequest pr : info.getPrs()) {
				boolean merged = "MERGED".equalsIgnoreCase(pr.getState());
				events.add(new GitEvent(merged ? "merge" : "pr", "PR #" + pr.getNumber(), pr.getTitle(),
						shortRepo(pr.getRepo()), authorName(pr.getAuthorId(), nameCache), pr.getAt(), key));
			}
			for (GitDevInfo.Build b : info.getBuilds()) {
				String ref = b.getBranch() != null ? b.getBranch() : b.getWorkflow();
				events.add(new GitEvent("deploy", ref, b.getName(),
						shortRepo(b.getRepo()), null, b.getAt(), key));
			}
		}
		events.sort(Comparator.comparing((GitEvent e) -> e.at() != null ? e.at() : Instant.EPOCH)
				.reversed());
		return events.size() > 6 ? events.subList(0, 6) : events;
	}

	private String authorName(String userId, Map<String, String> cache) {
		if (userId == null) {
			return null;
		}
		return cache.computeIfAbsent(userId,
				id -> users.findById(id).map(User::getDisplayName).orElse(null));
	}

	private static String firstLine(String text) {
		if (text == null) {
			return "";
		}
		int nl = text.indexOf('\n');
		return (nl >= 0 ? text.substring(0, nl) : text).trim();
	}

	/** Last path segment of {@code owner/repo} for compact display. */
	private static String shortRepo(String repo) {
		if (repo == null) {
			return null;
		}
		int slash = repo.lastIndexOf('/');
		return slash >= 0 ? repo.substring(slash + 1) : repo;
	}
}
