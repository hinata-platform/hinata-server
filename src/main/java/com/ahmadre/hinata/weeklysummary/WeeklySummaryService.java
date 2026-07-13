package com.ahmadre.hinata.weeklysummary;

import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a caller's "Weekly Summary": the team's work over the last seven days
 * (completed / created counts, the top contributors, notable resolved issues and
 * the current sprint) plus the caller's own upcoming to-dos (open assigned issues
 * ordered by due date, overdue first). Powers both the in-app page
 * ({@code GET /api/v1/weekly-summary}) and the Monday digest e-mail, so the two
 * never diverge.
 */
@Service
@RequiredArgsConstructor
public class WeeklySummaryService {

	/** How far back the "week behind" window reaches. */
	private static final int WINDOW_DAYS = 7;

	/** Notable resolved issues surfaced as highlights. */
	private static final int HIGHLIGHTS = 6;

	/** Top contributors shown on the leaderboard. */
	private static final int CONTRIBUTORS = 8;

	/** Upcoming to-dos surfaced in the list (the count spans all of them). */
	private static final int UPCOMING = 8;

	private final ProjectService projects;
	private final UserRepository users;
	private final MongoTemplate mongo;

	// ─────────────────────────── DTOs ───────────────────────────

	/**
	 * A person and how many issues they resolved in the window — for the "top
	 * contributors" leaderboard. Name/title/avatar are resolved server-side so
	 * the client renders without extra lookups.
	 */
	public record Contributor(String userId, String displayName, String title,
			String avatarUrl, long completed) {
	}

	/**
	 * Snapshot of the caller's active sprint (if any) for the summary hero.
	 * Sprint-only fields ({@code day}/{@code days}/{@code points}) are 0 when
	 * there is no schedule; {@code null} when the caller has no running sprint.
	 */
	public record SprintSnapshot(String boardId, String name, String goal, int day,
			int days, int issuesDone, int issuesTotal, int points, int pointsTotal) {
	}

	/**
	 * The week behind: aggregate team activity across the caller's visible
	 * projects, plus the caller's personal contribution ({@code myCompleted} /
	 * {@code focusMinutes}).
	 */
	public record TeamSummary(long completed, long created, long myCompleted,
			int focusMinutes, List<Contributor> contributors, List<Issue> highlights,
			SprintSnapshot sprint) {
	}

	/** The week ahead: the caller's open assigned work, overdue called out. */
	public record Upcoming(long total, long overdue, List<Issue> items) {
	}

	/** The full summary for one user over the window {@code [weekStart, weekEnd]}. */
	public record WeeklySummary(LocalDate weekStart, LocalDate weekEnd, TeamSummary team,
			Upcoming upcoming) {

		/** True when there is nothing worth showing (drives the digest skip). */
		public boolean isEmpty() {
			return team.completed() == 0 && team.created() == 0 && upcoming.total() == 0;
		}
	}

	// ─────────────────────────── build ───────────────────────────

	/** Aggregates the weekly summary for {@code user} across their visible projects. */
	public WeeklySummary forUser(User user) {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate weekStart = today.minusDays(WINDOW_DAYS);
		Instant since = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();

		List<Project> visible = projects.visibleTo(user);
		List<String> scopedIds = visible.stream().map(Project::getId).toList();

		if (scopedIds.isEmpty()) {
			return new WeeklySummary(weekStart, today,
					new TeamSummary(0, 0, 0, focusMinutes(user, weekStart, today),
							List.of(), List.of(), null),
					new Upcoming(0, 0, List.of()));
		}

		Criteria inScope = Criteria.where("projectId").in(scopedIds)
				.and("archived").ne(true);

		long completed = mongo.count(Query.query(new Criteria().andOperator(
				Criteria.where("projectId").in(scopedIds),
				Criteria.where("archived").ne(true),
				Criteria.where("resolvedAt").gte(since))), Issue.class);
		long created = mongo.count(Query.query(new Criteria().andOperator(
				Criteria.where("projectId").in(scopedIds),
				Criteria.where("archived").ne(true),
				Criteria.where("createdAt").gte(since))), Issue.class);

		List<Issue> resolvedThisWeek = mongo.find(Query.query(new Criteria().andOperator(
				Criteria.where("projectId").in(scopedIds),
				Criteria.where("archived").ne(true),
				Criteria.where("resolvedAt").gte(since))), Issue.class);

		List<Contributor> contributors = contributors(resolvedThisWeek);
		long myCompleted = resolvedThisWeek.stream()
				.filter(i -> i.getAssigneeIds() != null && i.getAssigneeIds().contains(user.getId()))
				.count();

		List<Issue> highlights = resolvedThisWeek.stream()
				.sorted(Comparator.comparing(Issue::getResolvedAt,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(HIGHLIGHTS)
				.toList();

		TeamSummary team = new TeamSummary(completed, created, myCompleted,
				focusMinutes(user, weekStart, today), contributors, highlights,
				activeSprint(visible, scopedIds));

		return new WeeklySummary(weekStart, today, team, upcoming(user, inScope, today));
	}

	// ─────────────────────────── the week behind ───────────────────────────

	/** Scores resolved issues per (primary) assignee, richest first. */
	private List<Contributor> contributors(List<Issue> resolved) {
		Map<String, Long> points = resolved.stream()
				.map(Issue::getAssigneeId)
				.filter(id -> id != null && !id.isBlank())
				.collect(Collectors.groupingBy(id -> id, Collectors.counting()));
		List<Contributor> entries = new ArrayList<>();
		points.forEach((userId, count) -> users.findById(userId).ifPresent(u ->
				entries.add(new Contributor(u.getId(), u.getDisplayName(), u.getTitle(),
						u.getAvatarUrl(), count))));
		entries.sort(Comparator.comparingLong(Contributor::completed).reversed()
				.thenComparing(Contributor::displayName));
		return entries.size() > CONTRIBUTORS ? entries.subList(0, CONTRIBUTORS) : entries;
	}

	/** The caller's tracked focus minutes over the window. */
	private int focusMinutes(User user, LocalDate from, LocalDate to) {
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("userId").is(user.getId())
				.and("date").gte(from).lte(to)), WorkItem.class);
		return items.stream().mapToInt(WorkItem::getDurationMinutes).sum();
	}

	/**
	 * The caller's active sprint for the hero: the first running sprint on any of
	 * their visible boards. {@code null} when none is running.
	 */
	private SprintSnapshot activeSprint(List<Project> visible, List<String> scopedIds) {
		List<AgileBoard> boards = mongo.find(
				Query.query(Criteria.where("projectIds").in(scopedIds)), AgileBoard.class);
		Map<String, Set<String>> resolvedByProject = new HashMap<>();
		for (Project p : visible) {
			resolvedByProject.put(p.getId(), Set.copyOf(p.getResolvedStates()));
		}
		for (AgileBoard board : boards) {
			if (board.getActiveSprintId() == null) {
				continue;
			}
			Sprint sprint = mongo.findById(board.getActiveSprintId(), Sprint.class);
			if (sprint != null) {
				return sprintSnapshot(board, sprint, resolvedByProject);
			}
		}
		return null;
	}

	private SprintSnapshot sprintSnapshot(AgileBoard board, Sprint sprint,
			Map<String, Set<String>> resolvedByProject) {
		List<Issue> inSprint = mongo.find(
				Query.query(Criteria.where("sprintId").is(sprint.getId())), Issue.class);
		int pointsTotal = 0;
		int points = 0;
		int issuesDone = 0;
		for (Issue issue : inSprint) {
			int pts = issue.getStoryPoints() != null ? issue.getStoryPoints() : 0;
			pointsTotal += pts;
			boolean done = resolvedByProject.getOrDefault(issue.getProjectId(), Set.of())
					.contains(issue.getState());
			if (done) {
				points += pts;
				issuesDone++;
			}
		}
		int days = 0;
		int day = 0;
		if (sprint.getStartDate() != null && sprint.getEndDate() != null) {
			days = (int) Math.max(1, ChronoUnit.DAYS.between(sprint.getStartDate(), sprint.getEndDate()));
			long elapsed = ChronoUnit.DAYS.between(sprint.getStartDate(), LocalDate.now(ZoneOffset.UTC)) + 1;
			day = (int) Math.min(Math.max(elapsed, 1), days);
		}
		return new SprintSnapshot(board.getId(), sprint.getName(), sprint.getGoal(),
				day, days, issuesDone, inSprint.size(), points, pointsTotal);
	}

	// ─────────────────────────── the week ahead ───────────────────────────

	/**
	 * The caller's open assigned to-dos, overdue first then soonest due, then
	 * undated (by priority). {@code total} spans them all; {@code items} is capped.
	 */
	private Upcoming upcoming(User user, Criteria inScope, LocalDate today) {
		Criteria assignedToMe = new Criteria().orOperator(
				Criteria.where("assigneeIds").is(user.getId()),
				Criteria.where("assigneeId").is(user.getId()));
		Query query = Query.query(new Criteria().andOperator(
				inScope, assignedToMe, Criteria.where("resolvedAt").is(null)));
		List<Issue> open = mongo.find(query, Issue.class);
		long overdue = open.stream()
				.filter(i -> i.getDueDate() != null && i.getDueDate().isBefore(today))
				.count();
		List<Issue> items = open.stream()
				.sorted(upcomingOrder())
				.limit(UPCOMING)
				.toList();
		return new Upcoming(open.size(), overdue, items);
	}

	/** Dated tasks lead (soonest first); undated tasks trail, higher priority first. */
	private static Comparator<Issue> upcomingOrder() {
		return Comparator
				.comparing((Issue i) -> i.getDueDate() == null)
				.thenComparing(Issue::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Issue::getPriority, Comparator.nullsLast(Comparator.naturalOrder()));
	}
}
