package com.ahmadre.hinata.project;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.deletion.DeletionService;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;
	private final IssueService issueService;
	private final DeletionService deletion;
	private final CurrentUser currentUser;

	public record CreateProjectRequest(
			@NotBlank @Size(min = 2, max = 10) String key,
			@NotBlank @Size(max = 120) String name,
			@Size(max = 4000) String description,
			String color,
			String leadId) {
	}

	@GetMapping
	public List<Project> list(
			@RequestParam(required = false, defaultValue = "false") boolean archived) {
		User user = currentUser.require();
		return archived ? projectService.archivedVisibleTo(user) : projectService.visibleTo(user);
	}

	@GetMapping("/{id}")
	public Project get(@PathVariable String id) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertMember(project, user);
		return project;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Project create(@RequestBody @Valid CreateProjectRequest request) {
		User user = currentUser.require();
		Project project = Project.builder()
				.key(request.key())
				.name(request.name())
				.description(request.description())
				.color(request.color() != null ? request.color() : "#AEC6F4")
				.leadId(request.leadId())
				.build();
		return projectService.create(project, user);
	}

	@PatchMapping("/{id}")
	public Project update(@PathVariable String id, @RequestBody @Valid ProjectUpdateRequest request) {
		return projectService.applyUpdate(id, request, currentUser.require());
	}

	/** Issue count per workflow-state name — lets the settings UI warn before
	 * deleting a state that still has issues and offer to migrate them. */
	@GetMapping("/{id}/state-usage")
	public Map<String, Long> stateUsage(@PathVariable String id) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertMember(project, user);
		return projectService.stateUsage(project);
	}

	/** Permanently deletes a label from the project and every issue using it. */
	@DeleteMapping("/{id}/labels")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteLabel(@PathVariable String id, @RequestParam String label) {
		issueService.removeProjectLabel(id, label, currentUser.require());
	}

	/**
	 * Impact of deleting the project: how many boards, sprints, issues,
	 * attachments, articles and teams are affected, plus the projects the caller
	 * could migrate the issues into. Drives the confirmation dialog's warnings.
	 */
	@GetMapping("/{id}/deletion-impact")
	public DeletionService.ProjectImpact deletionImpact(@PathVariable String id) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertLeadOrAdmin(project, user);
		return deletion.projectImpact(project, user);
	}

	/**
	 * Deletes the project over SSE. {@code issueStrategy} ({@code delete} /
	 * {@code migrate}) is required when the project still has issues;
	 * {@code migrateToProjectId} names the target for {@code migrate}. Validation
	 * runs synchronously (returning a normal HTTP error) before the stream opens;
	 * the cascade then streams {@code progress} and a terminal {@code done}.
	 */
	@GetMapping(value = "/{id}/delete-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter deleteStream(@PathVariable String id,
			@RequestParam(required = false) String issueStrategy,
			@RequestParam(required = false) String migrateToProjectId) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertLeadOrAdmin(project, user);
		DeletionService.ProjectDeleteOptions options =
				deletion.validateProjectDelete(project, user, issueStrategy, migrateToProjectId);
		SseEmitter emitter = deletion.newEmitter();
		deletion.deleteProject(project, options, LocaleContextHolder.getLocale(), emitter);
		return emitter;
	}
}
