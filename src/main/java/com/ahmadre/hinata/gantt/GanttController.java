package com.ahmadre.hinata.gantt;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.moderation.freeze.FrozenIssues;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read model for the interactive Gantt timeline: the scheduled issues plus the
 * link graph between them, so the chart can draw dependency connectors instead
 * of bars floating side by side. Rescheduling happens through PATCH
 * /issues/{id} (startDate, dueDate, dependsOnIds), linking through
 * /issues/{id}/links.
 */
@Tag(name = "Gantt")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/gantt")
@RequiredArgsConstructor
public class GanttController {

	private final IssueRepository issues;
	private final ProjectService projects;
	private final IssueLinkGraphService graph;
	private final FrozenIssues frozenIssues;
	private final CurrentUser currentUser;

	/**
	 * One bar on the chart. Either date may be null — a start-only issue runs from
	 * its start, a due-only issue is a deadline the client renders as a milestone
	 * — but never both, since the query only returns dated issues.
	 */
	public record GanttTask(String id, String readableId, String title, String state,
			String type, String assigneeId, LocalDate startDate, LocalDate dueDate, boolean resolved,
			String parentId, List<String> dependsOnIds, int progressPercent) {
	}

	/** The chart in one round trip: its bars and the connectors between them. */
	public record GanttView(List<GanttTask> tasks, List<IssueLinkGraphService.LinkEdge> links) {
	}

	@GetMapping
	public GanttView tasks(@PathVariable String projectId) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		projects.assertMember(project, user);
		// findScheduled is a derived @Query, so this view inherits none of
		// IssueService.search's filters — a frozen issue's title and readable id were
		// rendered on its bar, and its id appeared in the connector graph below.
		// Filtered once, here, so both halves of the response see the same set.
		List<Issue> scheduled = frozenIssues.readable(issues.findScheduled(projectId));
		return new GanttView(scheduled.stream().map(issue -> toTask(issue, project)).toList(),
				graph.among(scheduled));
	}

	/**
	 * The link graph of a project on its own — for views that already hold their
	 * issues (the board timeline) and only need the connectors.
	 */
	@GetMapping("/links")
	public List<IssueLinkGraphService.LinkEdge> links(@PathVariable String projectId) {
		return graph.forProjects(List.of(projectId), currentUser.require());
	}

	private GanttTask toTask(Issue issue, Project project) {
		boolean resolved = project.getResolvedStates().contains(issue.getState());
		int progress = resolved ? 100 : progressFromTime(issue);
		return new GanttTask(issue.getId(), issue.getReadableId(), issue.getTitle(),
				issue.getState(), issue.getType().name(), issue.getAssigneeId(),
				issue.getStartDate(), issue.getDueDate(),
				resolved, issue.getParentId(), issue.getDependsOnIds(), progress);
	}

	private int progressFromTime(Issue issue) {
		if (issue.getEstimateMinutes() == null || issue.getEstimateMinutes() == 0) {
			return 0;
		}
		return Math.min(99, issue.getSpentMinutes() * 100 / issue.getEstimateMinutes());
	}
}
