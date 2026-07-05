package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only MCP tools over agile boards and sprints. Gates on
 * {@code boards:read}; board visibility mirrors the board controller and
 * {@link SprintService}: a board is accessible when it spans at least one
 * project the caller may see. Issues on a board are read through
 * {@code search_issues} (with a sprint / backlog filter) — these tools return
 * the board structure, the sprint list and the sprint insights report.
 */
@Service
@RequiredArgsConstructor
public class BoardTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final AgileBoardRepository boards;
	private final SprintService sprintService;
	private final ProjectService projectService;

	@McpTool(name = "list_boards", title = "List boards",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the agile boards the caller can open (boards spanning at least "
					+ "one project visible to the caller), optionally restricted to one project.")
	public List<BoardView> listBoards(
			@McpToolParam(required = false, description = "Only boards that include this project id") String projectId) {
		scopeGuard.require(Scopes.BOARDS_READ);
		User user = currentUser.require();
		List<AgileBoard> all = projectId != null
				? boards.findByProjectIdsContains(projectId)
				: boards.findAll();
		Set<String> visible = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		return all.stream()
				.filter(b -> user.isAdmin() || b.getProjectIds().stream().anyMatch(visible::contains))
				.map(BoardView::of)
				.toList();
	}

	@McpTool(name = "get_board", title = "Get a board",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Fetch a board's structure: type (KANBAN or SCRUM), projects, columns "
					+ "with workflow states and WIP limits, and the active sprint id. Use "
					+ "search_issues with a sprintId (or noSprint for the backlog) to read the "
					+ "issues on the board.")
	public BoardView getBoard(
			@McpToolParam(description = "Board id") String boardId) {
		scopeGuard.require(Scopes.BOARDS_READ);
		User user = currentUser.require();
		return BoardView.of(sprintService.accessibleBoard(boardId, user));
	}

	@McpTool(name = "list_sprints", title = "List sprints",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List a board's sprints, newest first. Archived (completed) sprints are "
					+ "excluded unless includeArchived is true.")
	public List<SprintView> listSprints(
			@McpToolParam(description = "Board id") String boardId,
			@McpToolParam(required = false, description = "Include archived (completed) sprints") Boolean includeArchived) {
		scopeGuard.require(Scopes.BOARDS_READ);
		User user = currentUser.require();
		return sprintService.list(boardId, Boolean.TRUE.equals(includeArchived), user).stream()
				.map(SprintView::of)
				.toList();
	}

	@McpTool(name = "get_sprint_report", title = "Get a sprint report",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The insights report of a sprint: committed / completed / remaining "
					+ "story points, burndown, velocity history, scope changes and per-assignee "
					+ "load — ideal for stand-ups and retros.")
	public SprintService.SprintReport getSprintReport(
			@McpToolParam(description = "Sprint id") String sprintId) {
		scopeGuard.require(Scopes.BOARDS_READ);
		User user = currentUser.require();
		return sprintService.report(sprintId, user);
	}

	/** A board's structure without issue payloads or owner internals. */
	public record BoardView(String id, String name, String type, List<String> projectIds,
			String activeSprintId, List<ColumnView> columns, Instant createdAt) {

		static BoardView of(AgileBoard board) {
			return new BoardView(board.getId(), board.getName(),
					board.getType() == null ? null : board.getType().name(),
					board.getProjectIds(), board.getActiveSprintId(),
					board.getColumns() == null ? List.of() : board.getColumns().stream()
							.map(c -> new ColumnView(c.getName(), c.getStates(), c.getWipLimit()))
							.toList(),
					board.getCreatedAt());
		}
	}

	/** One board column: its name, the workflow states it shows and the WIP limit. */
	public record ColumnView(String name, List<String> states, Integer wipLimit) {
	}

	/** A sprint as planned on a board. */
	public record SprintView(String id, String boardId, String name, String goal,
			LocalDate startDate, LocalDate endDate, Integer capacityPoints, boolean archived,
			Instant createdAt) {

		static SprintView of(Sprint sprint) {
			return new SprintView(sprint.getId(), sprint.getBoardId(), sprint.getName(),
					sprint.getGoal(), sprint.getStartDate(), sprint.getEndDate(),
					sprint.getCapacityPoints(), sprint.isArchived(), sprint.getCreatedAt());
		}
	}
}
