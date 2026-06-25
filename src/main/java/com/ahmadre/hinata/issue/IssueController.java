package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Issues")
@RestController
@RequestMapping("/api/v1/issues")
@RequiredArgsConstructor
public class IssueController {

	private final IssueService issueService;
	private final CurrentUser currentUser;

	public record CreateIssueRequest(
			@NotBlank String projectId,
			@NotBlank @Size(max = 300) String title,
			@Size(max = 30000) String description,
			Issue.Type type,
			Issue.Priority priority,
			String state,
			String assigneeId,
			String parentId,
			String sprintId,
			List<String> tags,
			LocalDate startDate,
			LocalDate dueDate,
			Integer estimateMinutes,
			Integer storyPoints) {
	}

	public record UpdateIssueRequest(
			@Size(max = 300) String title,
			@Size(max = 30000) String description,
			Issue.Type type,
			Issue.Priority priority,
			String state,
			String assigneeId,
			String parentId,
			String sprintId,
			List<String> tags,
			List<String> dependsOnIds,
			LocalDate startDate,
			LocalDate dueDate,
			Integer estimateMinutes,
			Integer storyPoints,
			Double rank,
			// Explicit clear flags — JSON null on a field is "no change",
			// so clearing a value requires its own signal.
			Boolean clearStartDate,
			Boolean clearDueDate,
			Boolean clearStoryPoints) {
	}

	public record CommentRequest(@NotBlank @Size(max = 10000) String text) {
	}

	@GetMapping
	public Page<Issue> search(
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String assigneeId,
			@RequestParam(required = false) String sprintId,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "false") boolean noSprint,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return issueService.search(projectId, state, assigneeId, sprintId, type, query, noSprint,
				page, size, currentUser.require());
	}

	@GetMapping("/{id}")
	public Issue get(@PathVariable String id) {
		return issueService.getForUser(id, currentUser.require());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Issue create(@RequestBody @Valid CreateIssueRequest request) {
		Issue issue = Issue.builder()
				.projectId(request.projectId())
				.title(request.title())
				.description(request.description())
				.type(request.type() != null ? request.type() : Issue.Type.TASK)
				.priority(request.priority() != null ? request.priority() : Issue.Priority.NORMAL)
				.state(request.state())
				.assigneeId(request.assigneeId())
				.parentId(request.parentId())
				.sprintId(request.sprintId())
				.tags(request.tags() != null ? request.tags() : List.of())
				.startDate(request.startDate())
				.dueDate(request.dueDate())
				.estimateMinutes(request.estimateMinutes())
				.storyPoints(request.storyPoints())
				.build();
		return issueService.create(issue, currentUser.require());
	}

	@PatchMapping("/{id}")
	public Issue update(@PathVariable String id, @RequestBody @Valid UpdateIssueRequest request) {
		return issueService.update(id, issue -> {
			if (request.title() != null) issue.setTitle(request.title());
			if (request.description() != null) issue.setDescription(request.description());
			if (request.type() != null) issue.setType(request.type());
			if (request.priority() != null) issue.setPriority(request.priority());
			if (request.state() != null) issue.setState(request.state());
			if (request.assigneeId() != null) {
				issue.setAssigneeId(request.assigneeId().isBlank() ? null : request.assigneeId());
			}
			if (request.parentId() != null) {
				issue.setParentId(request.parentId().isBlank() ? null : request.parentId());
			}
			if (request.sprintId() != null) {
				issue.setSprintId(request.sprintId().isBlank() ? null : request.sprintId());
			}
			if (request.tags() != null) issue.setTags(request.tags());
			if (request.dependsOnIds() != null) issue.setDependsOnIds(request.dependsOnIds());
			if (Boolean.TRUE.equals(request.clearStartDate())) {
				issue.setStartDate(null);
			} else if (request.startDate() != null) {
				issue.setStartDate(request.startDate());
			}
			if (Boolean.TRUE.equals(request.clearDueDate())) {
				issue.setDueDate(null);
			} else if (request.dueDate() != null) {
				issue.setDueDate(request.dueDate());
			}
			if (request.estimateMinutes() != null) issue.setEstimateMinutes(request.estimateMinutes());
			if (Boolean.TRUE.equals(request.clearStoryPoints())) {
				issue.setStoryPoints(null);
			} else if (request.storyPoints() != null) {
				issue.setStoryPoints(request.storyPoints());
			}
			if (request.rank() != null) issue.setRank(request.rank());
		}, currentUser.require());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		issueService.delete(id, currentUser.require());
	}

	@GetMapping("/{id}/activity")
	public List<IssueActivity> activity(@PathVariable String id) {
		return issueService.activityOf(id, currentUser.require());
	}

	/** Breadcrumb ancestors + direct children for the issue hierarchy view. */
	@GetMapping("/{id}/hierarchy")
	public IssueService.Hierarchy hierarchy(@PathVariable String id) {
		return issueService.hierarchyOf(id, currentUser.require());
	}

	@GetMapping("/{id}/comments")
	public List<IssueComment> comments(@PathVariable String id) {
		return issueService.commentsOf(id, currentUser.require());
	}

	@PostMapping("/{id}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public IssueComment comment(@PathVariable String id, @RequestBody @Valid CommentRequest request) {
		return issueService.addComment(id, request.text(), currentUser.require());
	}

	@PatchMapping("/{id}/comments/{commentId}")
	public IssueComment editComment(@PathVariable String id, @PathVariable String commentId,
			@RequestBody @Valid CommentRequest request) {
		return issueService.editComment(id, commentId, request.text(), currentUser.require());
	}

	@DeleteMapping("/{id}/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComment(@PathVariable String id, @PathVariable String commentId) {
		issueService.deleteComment(id, commentId, currentUser.require());
	}
}
