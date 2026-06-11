package hn.asta.hivora.board;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.issue.IssueRepository;
import hn.asta.hivora.project.Project;
import hn.asta.hivora.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Tag(name = "Boards")
@RestController
@RequestMapping("/api/v1/boards")
@RequiredArgsConstructor
public class BoardController {

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final IssueRepository issues;
	private final ProjectService projects;
	private final CurrentUser currentUser;

	public record CreateBoardRequest(@NotBlank @Size(max = 120) String name,
			@NotEmpty List<String> projectIds) {
	}

	public record SprintRequest(@NotBlank @Size(max = 120) String name, String goal,
			LocalDate startDate, LocalDate endDate) {
	}

	public record BoardColumnView(String name, List<String> states, Integer wipLimit,
			List<Issue> issues) {
	}

	public record BoardView(AgileBoard board, List<Sprint> sprints, List<BoardColumnView> columns) {
	}

	@GetMapping
	public List<AgileBoard> list() {
		currentUser.require();
		return boards.findAll();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AgileBoard create(@RequestBody @Valid CreateBoardRequest request) {
		String userId = currentUser.requireId();
		// Default columns mirror the first project's workflow.
		Project first = projects.get(request.projectIds().get(0));
		List<AgileBoard.Column> columns = new ArrayList<>();
		for (String state : first.getWorkflowStates()) {
			columns.add(AgileBoard.Column.builder().name(state).states(List.of(state)).build());
		}
		return boards.save(AgileBoard.builder()
				.name(request.name())
				.projectIds(request.projectIds())
				.columns(columns)
				.ownerId(userId)
				.build());
	}

	@GetMapping("/{id}")
	public BoardView view(@PathVariable String id, @RequestParam(required = false) String sprintId) {
		currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("Board"));
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(id);
		String effectiveSprint = sprintId != null ? sprintId : board.getActiveSprintId();

		List<Issue> candidates = new ArrayList<>();
		for (String projectId : board.getProjectIds()) {
			if (effectiveSprint != null) {
				candidates.addAll(issues.findByProjectIdAndSprintId(projectId, effectiveSprint));
			}
			else {
				candidates.addAll(issues.findByProjectId(projectId,
						org.springframework.data.domain.PageRequest.of(0, 500)).getContent());
			}
		}
		candidates.sort(Comparator.comparingDouble(Issue::getRank));

		List<BoardColumnView> columnViews = new ArrayList<>();
		for (AgileBoard.Column column : board.getColumns()) {
			List<Issue> inColumn = candidates.stream()
					.filter(issue -> column.getStates().contains(issue.getState()))
					.toList();
			columnViews.add(new BoardColumnView(column.getName(), column.getStates(),
					column.getWipLimit(), inColumn));
		}
		return new BoardView(board, boardSprints, columnViews);
	}

	@PatchMapping("/{id}")
	public AgileBoard update(@PathVariable String id, @RequestBody AgileBoard updated) {
		currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("Board"));
		if (updated.getName() != null) board.setName(updated.getName());
		if (updated.getProjectIds() != null && !updated.getProjectIds().isEmpty()) {
			board.setProjectIds(updated.getProjectIds());
		}
		if (updated.getColumns() != null && !updated.getColumns().isEmpty()) {
			board.setColumns(updated.getColumns());
		}
		board.setActiveSprintId(updated.getActiveSprintId());
		return boards.save(board);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		currentUser.require();
		boards.deleteById(id);
	}

	@PostMapping("/{id}/sprints")
	@ResponseStatus(HttpStatus.CREATED)
	public Sprint createSprint(@PathVariable String id, @RequestBody @Valid SprintRequest request) {
		currentUser.require();
		boards.findById(id).orElseThrow(() -> ApiException.notFound("Board"));
		return sprints.save(Sprint.builder()
				.boardId(id)
				.name(request.name())
				.goal(request.goal())
				.startDate(request.startDate())
				.endDate(request.endDate())
				.build());
	}
}
