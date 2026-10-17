package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
	private final ProjectRepository projectStore;
	private final ProjectReach reach;
	private final IssueService issues;
	private final CurrentUser currentUser;

	@GetMapping("/api/v1/projects/{projectId}/time-usage")
	public TimeUsageService.ProjectUsage project(@PathVariable String projectId) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		if (!reach.canSee(project, user)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return usage.ofProjects(List.of(project.getId())).getFirst();
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
		Set<String> granted = reach.teamGrantedProjectIds(user);
		Set<String> visible = projectStore.findAllById(asked).stream()
				.filter(project -> granted.contains(project.getId())
						|| (project.getMemberIds() != null && project.getMemberIds().contains(user.getId())))
				.map(Project::getId)
				.collect(java.util.stream.Collectors.toSet());
		// In the order asked, so a list can lay the answers next to its rows.
		return usage.ofProjects(asked.stream().filter(visible::contains).toList());
	}

	@GetMapping("/api/v1/issues/{issueIdOrKey}/time-usage")
	public TimeUsageService.IssueUsage issue(@PathVariable String issueIdOrKey) {
		User user = currentUser.require();
		Issue issue = issues.getForUser(issueIdOrKey, user);
		return usage.ofIssue(issue);
	}
}
