package com.ahmadre.hinata.dashboard;

import com.ahmadre.hinata.auth.CurrentUser;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

	public record DashboardData(List<Issue> todayTasks, ProjectCompletion completion,
			List<RankEntry> ranking, List<TrackerDay> tracker) {
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
				tracker(user));
	}

	private List<Issue> todayTasks(User user) {
		LocalDate today = LocalDate.now();
		Query query = Query.query(Criteria.where("assigneeId").is(user.getId())
				.and("resolvedAt").is(null)
				.orOperator(
						Criteria.where("dueDate").lte(today),
						Criteria.where("priority").in("SHOWSTOPPER", "CRITICAL", "MAJOR")));
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
}
