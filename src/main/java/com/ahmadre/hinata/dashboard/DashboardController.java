package com.ahmadre.hinata.dashboard;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.git.GitDevInfo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

	public record ProjectCompletion(long done, long inProgress, long backlog, long total) {
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

	/** Snapshot of the board's active sprint, or {@code null} when none runs. */
	public record SprintSummary(String boardId, String sprintId, String name, String goal,
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
			SprintSummary activeSprint, List<GitEvent> gitActivity) {
	}

	@GetMapping
	public DashboardData dashboard() {
		User user = currentUser.require();
		List<Project> visible = projects.visibleTo(user);
		List<String> projectIds = visible.stream().map(Project::getId).toList();
		return new DashboardData(
				todayTasks(user),
				completion(visible, projectIds),
				ranking(projectIds),
				tracker(user),
				trackerMonth(user),
				activeSprint(visible, projectIds),
				gitActivity(projectIds));
	}

	private List<Issue> todayTasks(User user) {
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

	/** Resolved issues in the last 30 days, scored per assignee. */
	private List<RankEntry> ranking(List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
		List<Issue> resolved = mongo.find(Query.query(Criteria.where("projectId").in(projectIds)
				.and("resolvedAt").gte(since).and("assigneeId").ne(null)), Issue.class);
		Map<String, Long> points = resolved.stream()
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
	 * The active sprint of the first accessible SCRUM board that has one, with
	 * points/issue progress derived live from its issues and members inferred
	 * from their assignees. Returns {@code null} when no sprint is running.
	 */
	private SprintSummary activeSprint(List<Project> visible, List<String> projectIds) {
		if (projectIds.isEmpty()) {
			return null;
		}
		List<AgileBoard> boards = mongo.find(Query.query(Criteria.where("projectIds").in(projectIds)
				.and("activeSprintId").ne(null)), AgileBoard.class);
		if (boards.isEmpty()) {
			return null;
		}
		AgileBoard board = boards.get(0);
		Sprint sprint = mongo.findById(board.getActiveSprintId(), Sprint.class);
		if (sprint == null) {
			return null;
		}
		Map<String, Set<String>> resolvedByProject = new HashMap<>();
		for (Project p : visible) {
			resolvedByProject.put(p.getId(), Set.copyOf(p.getResolvedStates()));
		}
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
		List<SprintMember> memberList = members.stream().limit(6)
				.map(id -> users.findById(id)
						.map(u -> new SprintMember(u.getId(), u.getDisplayName(), u.getAvatarUrl()))
						.orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
		return new SprintSummary(board.getId(), sprint.getId(), sprint.getName(), sprint.getGoal(),
				day, days, points, pointsTotal, issuesDone, inSprint.size(), memberList);
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
