package hn.asta.hivora.project;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.issue.IssueService;
import hn.asta.hivora.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;
	private final IssueService issueService;
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

	/** Permanently deletes a label from the project and every issue using it. */
	@DeleteMapping("/{id}/labels")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteLabel(@PathVariable String id, @RequestParam String label) {
		issueService.removeProjectLabel(id, label, currentUser.require());
	}
}
