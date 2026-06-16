package hn.asta.hivora.board;

import hn.asta.hivora.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint planning, lifecycle and insights for Scrum boards. All endpoints are
 * scoped to a board the caller can access (admins, or members of one of the
 * board's projects); see {@link SprintService}.
 */
@Tag(name = "Sprints")
@RestController
@RequestMapping("/api/v1/sprints")
@RequiredArgsConstructor
public class SprintController {

	private final SprintService sprintService;
	private final CurrentUser currentUser;

	public record CreateSprintRequest(@NotBlank String boardId,
			@NotBlank @Size(max = 120) String name, @Size(max = 2000) String goal,
			LocalDate startDate, LocalDate endDate, Integer capacityPoints) {
	}

	public record UpdateSprintRequest(@Size(max = 120) String name, @Size(max = 2000) String goal,
			LocalDate startDate, LocalDate endDate, Integer capacityPoints) {
	}

	public record StartSprintRequest(@Size(max = 2000) String goal, LocalDate endDate) {
	}

	public record CompleteSprintRequest(String moveOpenTo) {
	}

	@GetMapping
	public List<Sprint> list(@RequestParam String boardId,
			@RequestParam(defaultValue = "false") boolean archived) {
		return sprintService.list(boardId, archived, currentUser.require());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Sprint create(@RequestBody @Valid CreateSprintRequest request) {
		return sprintService.create(request.boardId(), request.name(), request.goal(),
				request.startDate(), request.endDate(), request.capacityPoints(), currentUser.require());
	}

	@PatchMapping("/{id}")
	public Sprint update(@PathVariable String id, @RequestBody @Valid UpdateSprintRequest request) {
		return sprintService.update(id, request.name(), request.goal(), request.startDate(),
				request.endDate(), request.capacityPoints(), currentUser.require());
	}

	@PostMapping("/{id}/start")
	public Sprint start(@PathVariable String id, @RequestBody(required = false) StartSprintRequest request) {
		StartSprintRequest body = request != null ? request : new StartSprintRequest(null, null);
		return sprintService.start(id, body.goal(), body.endDate(), currentUser.require());
	}

	@PostMapping("/{id}/complete")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void complete(@PathVariable String id,
			@RequestBody(required = false) CompleteSprintRequest request) {
		String moveOpenTo = request != null ? request.moveOpenTo() : null;
		sprintService.complete(id, moveOpenTo, currentUser.require());
	}

	@GetMapping("/{id}/report")
	public SprintService.SprintReport report(@PathVariable String id) {
		return sprintService.report(id, currentUser.require());
	}
}
