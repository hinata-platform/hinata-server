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

import java.util.ArrayList;
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
	public record TopBudgets(List<TimeUsageService.ProjectUsage> items, Map<String, String> names, int total,
			Map<String, TimeHistoryService.Forecast> forecasts) {
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
		Ranked ranked = ranked(user, usage.projectsWithTimeBudget(projectIds));
		List<TimeUsageService.ProjectUsage> shown = usage.withMoney(ranked.slice(0, capped), user);
		return new TopBudgets(shown, ranked.namesOf(shown), ranked.rows().size(), history.forecasts(shown));
	}

	/** Budgeted projects the reader sees, active ones only, fullest time budget first, with their names. */
	private record Ranked(List<TimeUsageService.ProjectUsage> rows, Map<String, String> names) {

		List<TimeUsageService.ProjectUsage> slice(int from, int count) {
			int start = Math.min(rows.size(), from);
			return rows.subList(start, Math.min(rows.size(), start + count));
		}

		Map<String, String> namesOf(List<TimeUsageService.ProjectUsage> shown) {
			Map<String, String> of = new HashMap<>();
			shown.forEach(row -> of.put(row.projectId(), names.get(row.projectId())));
			return of;
		}
	}

	/**
	 * [budgeted] narrowed to what [user] sees and ranked. Money is added only to the rows shown,
	 * afterwards: it costs a valuation per project, and the ranking is by time alone.
	 */
	private Ranked ranked(User user, List<String> budgeted) {
		Map<String, String> names = new HashMap<>();
		projects.visibleAmong(user, budgeted).stream()
				.filter(project -> !project.isArchived())
				.forEach(project -> names.put(project.getId(), project.getName()));
		List<TimeUsageService.ProjectUsage> rows = usage.ofProjects(List.copyOf(names.keySet())).stream()
				.sorted(Comparator.comparingDouble(TimeUsageService.ProjectUsage::budgetFraction).reversed()
						.thenComparing(TimeUsageService.ProjectUsage::projectId))
				.toList();
		return new Ranked(rows, names);
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

	/** One page of the budget report and, on the first, the sprints with a budget (HIN-157). */
	public record BudgetReport(List<TimeUsageService.ProjectUsage> items, Map<String, String> names, int total,
			int page, int size, List<SprintRow> sprints, Map<String, TimeHistoryService.Forecast> forecasts) {
	}

	/** A sprint with a budget, on a board the reader sees, not archived. */
	public record SprintRow(String sprintId, String name, String boardName, Integer budgetMinutes, long usedMinutes,
			int nearPercent) {
	}

	/** How many rows one page of the budget report holds at most. */
	static final int REPORT_PAGE_MAX = 50;

	/** How many sprints the report lists at most. */
	static final int REPORT_SPRINTS_MAX = 20;

	/**
	 * Every budget the reader sees, fullest first, a page at a time (HIN-157): the report page's
	 * Budgets view. Sums of whole projects and sprints, never per person; money only for those who
	 * may see it.
	 */
	@GetMapping("/api/v1/time/reports/budgets")
	public BudgetReport budgetReport(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(required = false) List<String> projectIds) {
		User user = currentUser.require();
		int pageSize = Math.clamp(size, 1, REPORT_PAGE_MAX);
		int index = Math.max(0, page);
		Ranked ranked = ranked(user, usage.projectsWithBudgetFor(user, projectIds));
		List<TimeUsageService.ProjectUsage> shown = usage.withMoney(ranked.slice(index * pageSize, pageSize), user);
		return new BudgetReport(shown, ranked.namesOf(shown), ranked.rows().size(), index, pageSize,
				index == 0 ? sprintRows(user) : List.of(), history.forecasts(shown));
	}

	private List<SprintRow> sprintRows(User user) {
		// The reader's projects once, not once per sprint.
		java.util.Set<String> visible = projects.visibleTo(user).stream().map(Project::getId)
				.collect(Collectors.toSet());
		return history.budgetedSprintsAmong(visible, REPORT_SPRINTS_MAX).stream()
				.map(row -> new SprintRow(row.sprint().getId(), row.sprint().getName(), row.boardName(),
						row.usage().budgetMinutes(), row.usage().usedMinutes(), row.usage().nearPercent()))
				.toList();
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
