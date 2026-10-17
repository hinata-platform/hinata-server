package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Budget and estimate usage (HIN-152): what a project and an issue have used, for the bars, capsules
 * and rings that show it.
 *
 * <p>Anyone who sees the project reads it. A budget is a fact about the work, and somebody about to
 * record time against it should see how much is left; the sums are of the whole project and name
 * nobody. Gated with the extended module ({@link AdvancedTimeTrackingGate}).
 */
@Tag(name = "Time Tracking")
@RestController
@RequiredArgsConstructor
public class TimeUsageController {

	private final TimeUsageService usage;
	private final ProjectService projects;
	private final ProjectReach reach;
	private final IssueService issues;
	private final CurrentUser currentUser;
	private final TimeHistoryService history;
	private final java.time.Clock clock;

	@GetMapping("/api/v1/projects/{projectId}/time-usage")
	public TimeUsageService.ProjectUsage project(@PathVariable String projectId) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		if (!reach.canSee(project, user)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return usage.withMoney(usage.ofProjects(List.of(project.getId())), user).getFirst();
	}

	/**
	 * Several projects at once, for a list or the dashboard. Projects the caller cannot see, or that
	 * do not exist, are left out rather than refused: the answer to "which of these" must not tell
	 * which of the others exist.
	 */
	@GetMapping("/api/v1/projects/time-usage")
	public List<TimeUsageService.ProjectUsage> projects(@RequestParam List<String> ids) {
		User user = currentUser.require();
		Set<String> asked = new LinkedHashSet<>(ids);
		if (asked.size() > TimeUsageService.BATCH_MAX) {
			throw ApiException.badRequest("error.time.usageBatchTooLarge", TimeUsageService.BATCH_MAX);
		}
		// One query that carries the caller's reach, membership and team grants alike.
		Set<String> visible = projects.resolveVisible(user, List.copyOf(asked)).stream()
				.map(Project::getId)
				.collect(Collectors.toSet());
		// In the order asked, so a list can lay the answers next to its rows.
		return usage.withMoney(usage.ofProjects(asked.stream().filter(visible::contains).toList()), user);
	}

	/**
	 * The fullest budgets first, for the dashboard, with the projects' names so the cells can be
	 * labelled without a second read; {@code total} counts every budget the caller sees.
	 */
	public record TopBudgets(List<TimeUsageService.ProjectUsage> items, Map<String, String> names, int total) {
	}

	/**
	 * The caller's projects with a time budget, fullest first: the dashboard's honeycomb. Only active
	 * projects the caller sees, optionally narrowed to [projectIds] (the dashboard's own scope).
	 */
	@GetMapping("/api/v1/projects/time-usage/top")
	public TopBudgets top(@RequestParam(defaultValue = "3") int limit,
			@RequestParam(required = false) List<String> projectIds) {
		User user = currentUser.require();
		int capped = Math.clamp(limit, 1, TimeUsageService.TOP_MAX);
		List<String> budgeted = usage.projectsWithBudget(projectIds);
		Map<String, String> names = new HashMap<>();
		projects.visibleAmong(user, budgeted).stream()
				.filter(project -> !project.isArchived())
				.forEach(project -> names.put(project.getId(), project.getName()));
		List<TimeUsageService.ProjectUsage> ranked = usage.ofProjects(List.copyOf(names.keySet())).stream()
				.sorted(Comparator.comparingDouble(TimeUsageService.ProjectUsage::budgetFraction).reversed()
						.thenComparing(TimeUsageService.ProjectUsage::projectId))
				.toList();
		List<TimeUsageService.ProjectUsage> shown = usage.withMoney(
				ranked.subList(0, Math.min(capped, ranked.size())), user);
		Map<String, String> shownNames = new HashMap<>();
		shown.forEach(row -> shownNames.put(row.projectId(), names.get(row.projectId())));
		return new TopBudgets(shown, shownNames, ranked.size());
	}

	/**
	 * Recorded minutes per day for the burn-down (HIN-156): at most a year, the last 90 days unless
	 * asked otherwise. Sums of the whole project, never per person.
	 */
	@GetMapping("/api/v1/projects/{projectId}/time-usage/history")
	public TimeHistoryService.History history(@PathVariable String projectId,
			@RequestParam(required = false) java.time.LocalDate from,
			@RequestParam(required = false) java.time.LocalDate to) {
		Project project = visibleProject(projectId);
		java.time.LocalDate end = to != null ? to : java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
		java.time.LocalDate start = from != null ? from : end.minusDays(89);
		if (start.isAfter(end) || TimeHistoryService.span(start, end) > TimeHistoryService.HISTORY_MAX_DAYS) {
			throw ApiException.badRequest("error.time.historyRange", TimeHistoryService.HISTORY_MAX_DAYS);
		}
		return history.history(project.getId(), start, end);
	}

	/** When the budget runs out at the present pace (HIN-156). */
	@GetMapping("/api/v1/projects/{projectId}/time-usage/forecast")
	public TimeHistoryService.Forecast forecast(@PathVariable String projectId) {
		return history.forecast(visibleProject(projectId).getId());
	}

	private Project visibleProject(String projectId) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		if (!reach.canSee(project, user)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return project;
	}

	@GetMapping("/api/v1/issues/{issueIdOrKey}/time-usage")
	public TimeUsageService.IssueUsage issue(@PathVariable String issueIdOrKey) {
		User user = currentUser.require();
		Issue issue = issues.getForUser(issueIdOrKey, user);
		return usage.ofIssue(issue);
	}
}
