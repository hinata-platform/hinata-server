package hn.asta.hivora.board;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.issue.IssueActivity;
import hn.asta.hivora.issue.IssueActivityRepository;
import hn.asta.hivora.issue.IssueRepository;
import hn.asta.hivora.project.Project;
import hn.asta.hivora.project.ProjectService;
import hn.asta.hivora.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sprint lifecycle (plan → start → complete) and the insights report, all
 * scoped to a board the caller can access. Access is granted to admins and to
 * members of any of the board's projects (mirrors {@code IssueService}'s A01
 * rule). Story points are the unit of capacity, velocity and burndown.
 */
@Service
@RequiredArgsConstructor
public class SprintService {

	/** Reports never replay more than this many days to bound the work. */
	private static final int MAX_SPRINT_DAYS = 60;
	/** Velocity history window. */
	private static final int VELOCITY_COUNT = 5;

	private final SprintRepository sprints;
	private final AgileBoardRepository boards;
	private final IssueRepository issues;
	private final IssueActivityRepository activities;
	private final ProjectService projects;
	private final MongoTemplate mongo;

	// ── access ────────────────────────────────────────────────────────────

	public AgileBoard accessibleBoard(String boardId, User user) {
		AgileBoard board = boards.findById(boardId).orElseThrow(() -> ApiException.notFound("board"));
		assertAccess(board, user);
		return board;
	}

	private void assertAccess(AgileBoard board, User user) {
		if (user.isAdmin()) {
			return;
		}
		Set<String> visible = projects.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		if (board.getProjectIds().stream().noneMatch(visible::contains)) {
			throw ApiException.forbidden("error.accessDenied");
		}
	}

	public Sprint accessibleSprint(String sprintId, User user) {
		Sprint sprint = sprints.findById(sprintId).orElseThrow(() -> ApiException.notFound("sprint"));
		accessibleBoard(sprint.getBoardId(), user);
		return sprint;
	}

	// ── CRUD / lifecycle ──────────────────────────────────────────────────

	public List<Sprint> list(String boardId, boolean includeArchived, User user) {
		accessibleBoard(boardId, user);
		List<Sprint> all = sprints.findByBoardIdOrderByStartDateDesc(boardId);
		return includeArchived ? all : all.stream().filter(s -> !s.isArchived()).toList();
	}

	public Sprint create(String boardId, String name, String goal, LocalDate startDate,
			LocalDate endDate, Integer capacityPoints, User user) {
		AgileBoard board = accessibleBoard(boardId, user);
		if (board.getType() != AgileBoard.Type.SCRUM) {
			throw ApiException.badRequest("error.sprint.notScrumBoard");
		}
		if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
			throw ApiException.badRequest("error.sprint.endBeforeStart");
		}
		return sprints.save(Sprint.builder()
				.boardId(boardId)
				.name(name)
				.goal(goal)
				.startDate(startDate)
				.endDate(endDate)
				.capacityPoints(capacityPoints)
				.build());
	}

	public Sprint update(String id, String name, String goal, LocalDate startDate,
			LocalDate endDate, Integer capacityPoints, User user) {
		Sprint sprint = accessibleSprint(id, user);
		if (name != null && !name.isBlank()) sprint.setName(name);
		if (goal != null) sprint.setGoal(goal);
		if (startDate != null) sprint.setStartDate(startDate);
		if (endDate != null) sprint.setEndDate(endDate);
		if (capacityPoints != null) sprint.setCapacityPoints(capacityPoints);
		if (sprint.getStartDate() != null && sprint.getEndDate() != null
				&& sprint.getEndDate().isBefore(sprint.getStartDate())) {
			throw ApiException.badRequest("error.sprint.endBeforeStart");
		}
		return sprints.save(sprint);
	}

	/** Locks scope and marks this sprint the board's active sprint. */
	@Transactional
	public Sprint start(String id, String goal, LocalDate endDate, User user) {
		Sprint sprint = accessibleSprint(id, user);
		AgileBoard board = boards.findById(sprint.getBoardId())
				.orElseThrow(() -> ApiException.notFound("board"));
		if (goal != null) sprint.setGoal(goal);
		if (endDate != null) sprint.setEndDate(endDate);
		sprint.setArchived(false);
		Sprint saved = sprints.save(sprint);
		board.setActiveSprintId(saved.getId());
		boards.save(board);
		return saved;
	}

	/**
	 * Archives the sprint and re-homes every unfinished issue (not in a resolved
	 * state) to [moveOpenTo] — {@code "backlog"}/blank ⇒ no sprint, or a sibling
	 * sprint id on the same board. Each move is recorded as a SPRINT activity so
	 * the destination sprint's scope log stays accurate.
	 */
	@Transactional
	public void complete(String id, String moveOpenTo, User user) {
		Sprint sprint = accessibleSprint(id, user);
		AgileBoard board = boards.findById(sprint.getBoardId())
				.orElseThrow(() -> ApiException.notFound("board"));

		String target = null;
		if (moveOpenTo != null && !moveOpenTo.isBlank()
				&& !"backlog".equalsIgnoreCase(moveOpenTo)) {
			Sprint dest = sprints.findById(moveOpenTo)
					.orElseThrow(() -> ApiException.notFound("sprint"));
			if (!dest.getBoardId().equals(sprint.getBoardId())) {
				throw ApiException.badRequest("error.sprint.destinationBoardMismatch");
			}
			target = dest.getId();
		}

		List<IssueActivity> log = new ArrayList<>();
		for (Issue issue : issues.findBySprintId(sprint.getId())) {
			Project project = projects.get(issue.getProjectId());
			boolean resolved = project.getResolvedStates().contains(issue.getState());
			if (resolved) {
				continue; // finished work stays attributed to the completed sprint
			}
			String from = issue.getSprintId();
			issue.setSprintId(target);
			issues.save(issue);
			log.add(IssueActivity.builder()
					.issueId(issue.getId())
					.actorId(user != null ? user.getId() : null)
					.field(IssueActivity.Field.SPRINT)
					.fromValue(from)
					.toValue(target)
					.build());
		}
		if (!log.isEmpty()) activities.saveAll(log);

		sprint.setArchived(true);
		sprints.save(sprint);
		if (sprint.getId().equals(board.getActiveSprintId())) {
			board.setActiveSprintId(null);
			boards.save(board);
		}
	}

	// ── insights report ───────────────────────────────────────────────────

	/** {@code remaining} is null for days that haven't elapsed yet. */
	public record BurndownPoint(int day, LocalDate date, Double remaining, double ideal) {
	}

	public record VelocityPoint(String sprintId, String name, int committed, int completed) {
	}

	public record ScopeChange(LocalDate date, int delta, String label) {
	}

	public record AssigneeLoad(String userId, int done, int total) {
	}

	public record Summary(int committed, int completed, int remaining, int issuesDone,
			int issuesTotal, Integer capacityPoints, int avgVelocity) {
	}

	public record SprintReport(Summary summary, List<BurndownPoint> burndown,
			List<VelocityPoint> velocity, List<ScopeChange> scope, List<AssigneeLoad> breakdown) {
	}

	public SprintReport report(String id, User user) {
		Sprint sprint = accessibleSprint(id, user);
		Map<String, Set<String>> resolvedCache = new HashMap<>();
		List<Issue> inSprint = issues.findBySprintId(sprint.getId());

		int committed = inSprint.stream().mapToInt(SprintService::points).sum();
		List<Issue> doneIssues = inSprint.stream().filter(i -> resolvedIn(i, resolvedCache)).toList();
		int completed = doneIssues.stream().mapToInt(SprintService::points).sum();
		int remaining = committed - completed;

		List<VelocityPoint> velocity = velocity(sprint.getBoardId());
		int avg = velocity.isEmpty() ? 0
				: (int) Math.round(velocity.stream().mapToInt(VelocityPoint::completed).average().orElse(0));

		Summary summary = new Summary(committed, completed, remaining,
				doneIssues.size(), inSprint.size(), sprint.getCapacityPoints(), avg);

		return new SprintReport(summary, burndown(sprint, inSprint, committed),
				velocity, scopeChanges(sprint, inSprint), breakdown(inSprint, resolvedCache));
	}

	/** Committed/completed points per completed sprint, oldest → newest. */
	public List<VelocityPoint> velocity(String boardId) {
		List<Sprint> done = sprints.findByBoardIdAndArchivedTrueOrderByEndDateDesc(boardId);
		Map<String, Set<String>> resolvedCache = new HashMap<>();
		List<VelocityPoint> out = new ArrayList<>();
		for (Sprint s : done.stream().limit(VELOCITY_COUNT).toList()) {
			List<Issue> list = issues.findBySprintId(s.getId());
			int committed = list.stream().mapToInt(SprintService::points).sum();
			int completed = list.stream().filter(i -> resolvedIn(i, resolvedCache))
					.mapToInt(SprintService::points).sum();
			out.add(new VelocityPoint(s.getId(), s.getName(), committed, completed));
		}
		java.util.Collections.reverse(out);
		return out;
	}

	/** Story points still open at the end of each elapsed sprint day vs. the ideal line. */
	private List<BurndownPoint> burndown(Sprint sprint, List<Issue> inSprint, int committed) {
		LocalDate start = sprint.getStartDate();
		LocalDate end = sprint.getEndDate();
		if (start == null || end == null || end.isBefore(start)) {
			return List.of();
		}
		long totalDays = Math.min(ChronoUnit.DAYS.between(start, end), MAX_SPRINT_DAYS);
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		List<BurndownPoint> out = new ArrayList<>();
		for (int day = 0; day <= totalDays; day++) {
			LocalDate date = start.plusDays(day);
			double ideal = committed * (1.0 - (double) day / totalDays);
			Double remaining = null;
			if (!date.isAfter(today)) {
				LocalDate cutoff = date;
				int burned = inSprint.stream()
						.filter(i -> resolvedOnOrBefore(i, cutoff))
						.mapToInt(SprintService::points).sum();
				remaining = (double) (committed - burned);
			}
			out.add(new BurndownPoint(day, date, remaining, round1(ideal)));
		}
		return out;
	}

	/** SPRINT-field activity touching this sprint inside its window → scope deltas. */
	private List<ScopeChange> scopeChanges(Sprint sprint, List<Issue> inSprint) {
		if (sprint.getStartDate() == null) {
			return List.of();
		}
		Instant from = sprint.getStartDate().atStartOfDay().toInstant(ZoneOffset.UTC);
		Instant to = (sprint.getEndDate() != null ? sprint.getEndDate().plusDays(1)
				: LocalDate.now(ZoneOffset.UTC).plusDays(1)).atStartOfDay().toInstant(ZoneOffset.UTC);
		List<IssueActivity> log = mongo.find(Query.query(Criteria.where("field")
				.is(IssueActivity.Field.SPRINT.name())
				.and("createdAt").gte(from).lt(to)
				.orOperator(Criteria.where("fromValue").is(sprint.getId()),
						Criteria.where("toValue").is(sprint.getId()))), IssueActivity.class);
		if (log.isEmpty()) {
			return List.of();
		}
		Map<String, Issue> byId = new HashMap<>();
		for (Issue i : inSprint) byId.put(i.getId(), i);
		issues.findAllById(log.stream().map(IssueActivity::getIssueId).collect(Collectors.toSet()))
				.forEach(i -> byId.putIfAbsent(i.getId(), i));

		List<ScopeChange> out = new ArrayList<>();
		for (IssueActivity a : log) {
			Issue issue = byId.get(a.getIssueId());
			int pts = issue != null ? points(issue) : 0;
			boolean added = sprint.getId().equals(a.getToValue());
			int delta = added ? pts : -pts;
			if (delta == 0) {
				continue;
			}
			String label = issue != null ? issue.getReadableId() + " · " + issue.getTitle() : a.getIssueId();
			LocalDate date = a.getCreatedAt() != null
					? a.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate() : sprint.getStartDate();
			out.add(new ScopeChange(date, delta, label));
		}
		return out;
	}

	private List<AssigneeLoad> breakdown(List<Issue> inSprint, Map<String, Set<String>> resolvedCache) {
		Map<String, int[]> byUser = new LinkedHashMap<>(); // [done, total]
		for (Issue i : inSprint) {
			String user = i.getAssigneeId() != null ? i.getAssigneeId() : "";
			int[] cell = byUser.computeIfAbsent(user, k -> new int[2]);
			int pts = points(i);
			cell[1] += pts;
			if (resolvedIn(i, resolvedCache)) cell[0] += pts;
		}
		return byUser.entrySet().stream()
				.map(e -> new AssigneeLoad(e.getKey(), e.getValue()[0], e.getValue()[1]))
				.sorted((a, b) -> Integer.compare(b.total(), a.total()))
				.toList();
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private static int points(Issue issue) {
		return issue.getStoryPoints() != null ? issue.getStoryPoints() : 0;
	}

	private boolean resolvedIn(Issue issue, Map<String, Set<String>> cache) {
		Set<String> resolved = cache.computeIfAbsent(issue.getProjectId(),
				pid -> Set.copyOf(projects.get(pid).getResolvedStates()));
		return resolved.contains(issue.getState());
	}

	private boolean resolvedOnOrBefore(Issue issue, LocalDate date) {
		if (issue.getResolvedAt() == null) {
			return false;
		}
		return !issue.getResolvedAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(date);
	}

	private static double round1(double value) {
		return Math.round(value * 10.0) / 10.0;
	}
}
