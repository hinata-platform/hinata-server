package hn.asta.hivora.project;

import hn.asta.hivora.auth.CurrentUser;
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
	private final CurrentUser currentUser;

	public record CreateProjectRequest(
			@NotBlank @Size(min = 2, max = 10) String key,
			@NotBlank @Size(max = 120) String name,
			@Size(max = 4000) String description,
			String color) {
	}

	public record UpdateProjectRequest(
			@Size(max = 120) String name,
			@Size(max = 4000) String description,
			String leadId,
			List<String> memberIds,
			List<String> workflowStates,
			List<String> resolvedStates,
			String color,
			Boolean archived) {
	}

	@GetMapping
	public List<Project> list() {
		return projectService.visibleTo(currentUser.require());
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
				.build();
		return projectService.create(project, user);
	}

	@PatchMapping("/{id}")
	public Project update(@PathVariable String id, @RequestBody @Valid UpdateProjectRequest request) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertMember(project, user);
		if (request.name() != null) project.setName(request.name());
		if (request.description() != null) project.setDescription(request.description());
		if (request.leadId() != null) project.setLeadId(request.leadId());
		if (request.memberIds() != null) project.setMemberIds(request.memberIds());
		if (request.workflowStates() != null && !request.workflowStates().isEmpty()) {
			project.setWorkflowStates(request.workflowStates());
		}
		if (request.resolvedStates() != null) project.setResolvedStates(request.resolvedStates());
		if (request.color() != null) project.setColor(request.color());
		if (request.archived() != null) project.setArchived(request.archived());
		return projectService.save(project);
	}
}
