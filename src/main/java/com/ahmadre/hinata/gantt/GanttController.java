package com.ahmadre.hinata.gantt;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read model for the interactive Gantt timeline. Rescheduling happens through
 * PATCH /issues/{id} (startDate, dueDate, dependsOnIds).
 */
@Tag(name = "Gantt")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/gantt")
@RequiredArgsConstructor
public class GanttController {

	private final IssueRepository issues;
	private final ProjectService projects;
	private final CurrentUser currentUser;

	public record GanttTask(String id, String readableId, String title, String state,
			String type, String assigneeId, LocalDate startDate, LocalDate dueDate, boolean resolved,
			String parentId, List<String> dependsOnIds, int progressPercent) {
	}

	@GetMapping
	public List<GanttTask> tasks(@PathVariable String projectId) {
		currentUser.require();
		Project project = projects.get(projectId);
		return issues.findByProjectIdAndStartDateNotNull(projectId).stream()
				.map(issue -> toTask(issue, project))
				.toList();
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
