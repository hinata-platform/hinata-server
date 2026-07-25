package com.ahmadre.hinata.board;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.deletion.DeletionService;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Boards")
@RestController
@RequestMapping("/api/v1/boards")
@RequiredArgsConstructor
public class BoardController {

	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final IssueRepository issues;
	private final IssueService issueService;
	private final ProjectService projects;
	private final DeletionService deletion;
	private final CurrentUser currentUser;
	private final com.ahmadre.hinata.team.TeamRepository teams;

	public record CreateBoardRequest(@NotBlank @Size(max = 120) String name,
			@NotEmpty List<String> projectIds, AgileBoard.Type type) {
	}

	/** Partial board update — every field is optional (null = no change). */
	public record UpdateBoardRequest(@Size(max = 120) String name, AgileBoard.Type type,
			String activeSprintId, List<String> projectIds) {
	}

	public record SprintRequest(@NotBlank @Size(max = 120) String name, String goal,
			LocalDate startDate, LocalDate endDate) {
	}

	public record BoardColumnView(String name, List<String> states, Integer wipLimit,
			int hue, List<Issue> issues) {
	}

	public record BoardView(AgileBoard board, List<Sprint> sprints, List<BoardColumnView> columns) {
	}

	@GetMapping
	public List<AgileBoard> list(@RequestParam(required = false) String projectId) {
		User user = currentUser.require();
		List<AgileBoard> all = projectId != null
				? boards.findByProjectIdsContains(projectId)
				: boards.findAll();
		// Only surface boards the user may actually open. visibleTo already
		// excludes archived projects (a deactivated project's boards must never
		// surface) and applies direct-membership + team-grant access — mirroring
		// SprintService.assertAccess, so the overview matches what a card click
		// would allow. Admins see every active project's boards.
		Set<String> visible = visibleProjectIds(user);
		return all.stream()
				.filter(b -> b.getProjectIds().stream().anyMatch(visible::contains))
				.limit(LIST_CAP)
				.toList();
	}

	/** Backstop ceiling on the array-shaped board list (visibility already scopes
	 * it; this only guards a pathological org's unfiltered findAll path). */
	private static final int LIST_CAP = 500;

	/** Ids of the projects the user may see (deduped, archived excluded). */
	private Set<String> visibleProjectIds(User user) {
		return projects.visibleTo(user).stream().map(Project::getId).collect(Collectors.toSet());
	}

	/** The spanned projects that still exist, in the board's own order. */
	private List<Project> spannedProjects(List<String> projectIds) {
		List<Project> spanned = new ArrayList<>();
		for (String projectId : projectIds) {
			projects.findOptional(projectId).ifPresent(spanned::add);
		}
		return spanned;
	}

	/**
	 * The spanned projects a given viewer may actually work with: existing, not
	 * archived, and visible to them. A board may legitimately span several
	 * projects, but a viewer must never see issues — or even columns — of a
	 * project they cannot access, otherwise a shared cross-project board leaks a
	 * foreign backlog.
	 */
	private List<Project> viewableProjects(AgileBoard board, User user) {
		Set<String> active = projects.activeProjectIds();
		Set<String> visibleToViewer = user.isAdmin() ? null : visibleProjectIds(user);
		List<Project> viewable = new ArrayList<>();
		for (Project project : spannedProjects(board.getProjectIds())) {
			if (!active.contains(project.getId())) continue;
			if (visibleToViewer != null && !visibleToViewer.contains(project.getId())) continue;
			viewable.add(project);
		}
		return viewable;
	}

	/**
	 * Membership check on EVERY spanned project: without this a member of one
	 * project could craft (or widen) a board spanning a victim project and read
	 * its full backlog through {@link #view}. Admins are unrestricted.
	 */
	private void assertMemberOfAll(List<String> projectIds, User user) {
		if (user.isAdmin()) return;
		Set<String> visible = visibleProjectIds(user);
		for (String projectId : projectIds) {
			if (!visible.contains(projectId)) throw ApiException.forbidden("error.accessDenied");
		}
	}

	/** A board is accessible if it spans at least one project the user may see. */
	private void assertBoardAccess(AgileBoard board, User user) {
		if (user.isAdmin()) return;
		if (board.getProjectIds().stream().noneMatch(visibleProjectIds(user)::contains)) {
			throw ApiException.forbidden("error.accessDenied");
		}
	}

	/**
	 * Managing a board (rename / delete) is restricted to: the board's owner (the
	 * member who created it), a lead of any project the board spans, a Team-Admin
	 * of any team that owns such a project, and platform admins. Regular project
	 * members may use a board but not reconfigure it.
	 */
	private void assertBoardManage(AgileBoard board, User user) {
		if (canManageBoard(board, user)) return;
		throw ApiException.forbidden("error.board.notManager");
	}

	private boolean canManageBoard(AgileBoard board, User user) {
		if (user.isAdmin()) return true;
		if (user.getId().equals(board.getOwnerId())) return true;
		for (String projectId : board.getProjectIds()) {
			if (projects.findOptional(projectId).map(p -> isProjectLead(p, user)).orElse(false)) {
				return true;
			}
			for (com.ahmadre.hinata.team.Team team : teams.findByProjectIdsContains(projectId)) {
				if (team.isAdmin(user.getId())) return true;
			}
		}
		return false;
	}

	private boolean isProjectLead(Project project, User user) {
		if (user.getId().equals(project.getLeadId())) return true;
		return project.getLeadIds() != null && project.getLeadIds().contains(user.getId());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AgileBoard create(@RequestBody @Valid CreateBoardRequest request) {
		User user = currentUser.require();
		String userId = user.getId();
		assertMemberOfAll(request.projectIds(), user);
		// Default columns merge every spanned project's workflow: one column per
		// state for a single project (unchanged), and equivalent states of
		// different projects folded together for a cross-project board.
		List<AgileBoard.Column> columns = BoardColumns.merge(spannedProjects(request.projectIds()));
		return boards.save(AgileBoard.builder()
				.name(request.name())
				.type(request.type() != null ? request.type() : AgileBoard.Type.KANBAN)
				.projectIds(request.projectIds())
				.columns(columns)
				.ownerId(userId)
				.build());
	}

	@GetMapping("/{id}")
	public BoardView view(@PathVariable String id, @RequestParam(required = false) String sprintId) {
		User user = currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("board"));
		assertBoardAccess(board, user);
		List<Sprint> boardSprints = sprints.findByBoardIdOrderByStartDateDesc(id);
		String effectiveSprint = sprintId != null ? sprintId : board.getActiveSprintId();

		// Only the projects this viewer may actually work with — a shared
		// cross-project board must never leak a foreign project's backlog.
		List<Project> viewable = viewableProjects(board, user);

		List<Issue> candidates = new ArrayList<>();
		for (Project project : viewable) {
			if (effectiveSprint != null) {
				candidates.addAll(issues.findByProjectIdAndSprintId(project.getId(), effectiveSprint));
			}
			else {
				candidates.addAll(issues.findByProjectId(project.getId(),
						org.springframework.data.domain.PageRequest.of(0, 500)).getContent());
			}
		}
		// Archived issues are soft-deleted — they never appear on the board.
		candidates.removeIf(Issue::isArchived);
		candidates.sort(Comparator.comparingDouble(Issue::getRank));
		// Stamp each card with its direct-child (sub-task) count/progress so the
		// board can show the indicator + expander without a per-card lookup. The
		// column views below hold the same Issue instances, so this reaches them.
		issueService.enrichSubtaskCounts(candidates);

		// Columns are derived live from the viewable projects' *current* workflow
		// states — never the stale snapshot stored on the board — so renames /
		// additions / deletions in project settings are always reflected here. For
		// a single-project board this is one column per state, exactly as before;
		// across projects, equivalent states are merged into one column so the
		// board stays readable and a drop always has a state the card's own
		// project knows. WIP limits are carried over from the stored columns by
		// matching column name.
		List<AgileBoard.Column> columns = BoardColumns.merge(viewable);
		Map<String, Integer> hueByColumn = BoardColumns.hues(columns, viewable);

		Map<String, Integer> wipByName = new HashMap<>();
		for (AgileBoard.Column column : board.getColumns()) {
			if (column.getWipLimit() != null) wipByName.put(column.getName(), column.getWipLimit());
		}

		List<BoardColumnView> columnViews = new ArrayList<>();
		for (AgileBoard.Column column : columns) {
			Set<String> states = column.getStates().stream()
					.map(state -> state.toLowerCase(java.util.Locale.ROOT))
					.collect(Collectors.toSet());
			List<Issue> inColumn = candidates.stream()
					.filter(issue -> issue.getState() != null
							&& states.contains(issue.getState().toLowerCase(java.util.Locale.ROOT)))
					.toList();
			columnViews.add(new BoardColumnView(column.getName(), column.getStates(),
					wipByName.get(column.getName()),
					hueByColumn.getOrDefault(column.getName(), 250), inColumn));
		}
		return new BoardView(board, boardSprints, columnViews);
	}

	@PatchMapping("/{id}")
	public AgileBoard update(@PathVariable String id, @RequestBody UpdateBoardRequest req) {
		User user = currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("board"));
		assertBoardAccess(board, user);
		// Renaming is a management action — gate it.
		boolean renaming = req.name() != null && !req.name().equals(board.getName());
		if (renaming) {
			assertBoardManage(board, user);
		}
		if (req.name() != null) board.setName(req.name());
		if (req.type() != null) board.setType(req.type());
		// Only set when provided, so a rename doesn't wipe the active sprint.
		if (req.activeSprintId() != null) board.setActiveSprintId(req.activeSprintId());
		// Changing which projects a board spans is a management action, and the
		// caller must be a member of every project in the NEW set — otherwise
		// widening a board would be a way to read a foreign project's backlog.
		if (req.projectIds() != null) {
			if (req.projectIds().isEmpty()) {
				throw ApiException.badRequest("error.board.noProjects");
			}
			assertBoardManage(board, user);
			assertMemberOfAll(req.projectIds(), user);
			board.setProjectIds(new ArrayList<>(new LinkedHashSet<>(req.projectIds())));
			// Re-merge the stored column snapshot for the new span. The view derives
			// columns live anyway; this keeps the stored WIP-limit carrier in step
			// so a limit isn't orphaned on a column that no longer exists — limits
			// on columns that survive the re-merge are preserved by name.
			Map<String, Integer> wip = new HashMap<>();
			for (AgileBoard.Column column : board.getColumns()) {
				if (column.getWipLimit() != null) wip.put(column.getName(), column.getWipLimit());
			}
			List<AgileBoard.Column> merged = BoardColumns.merge(spannedProjects(board.getProjectIds()));
			for (AgileBoard.Column column : merged) {
				column.setWipLimit(wip.get(column.getName()));
			}
			board.setColumns(merged);
		}
		return boards.save(board);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = currentUser.require();
		boards.findById(id).ifPresent(board -> {
			assertBoardManage(board, user);
			// Same cascade as the streaming path: removes the board and its sprints,
			// and detaches (never deletes) the issues so no sprint reference dangles.
			deletion.deleteBoardNow(board);
		});
	}

	/** Counts that drive the delete confirmation (sprints, issues to detach). */
	@GetMapping("/{id}/deletion-impact")
	public DeletionService.BoardImpact deletionImpact(@PathVariable String id) {
		User user = currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("board"));
		assertBoardManage(board, user);
		return deletion.boardImpact(board);
	}

	/**
	 * Deletes the board over SSE, streaming {@code progress} steps and a terminal
	 * {@code done} summary so the client can show live progress. Only the board
	 * and its sprints are removed; issues are kept and detached.
	 */
	@GetMapping(value = "/{id}/delete-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter deleteStream(@PathVariable String id) {
		User user = currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("board"));
		assertBoardManage(board, user);
		SseEmitter emitter = deletion.newEmitter();
		deletion.deleteBoard(board, LocaleContextHolder.getLocale(), emitter);
		return emitter;
	}

	@PostMapping("/{id}/sprints")
	@ResponseStatus(HttpStatus.CREATED)
	public Sprint createSprint(@PathVariable String id, @RequestBody @Valid SprintRequest request) {
		User user = currentUser.require();
		AgileBoard board = boards.findById(id).orElseThrow(() -> ApiException.notFound("board"));
		assertBoardAccess(board, user);
		return sprints.save(Sprint.builder()
				.boardId(id)
				.name(request.name())
				.goal(request.goal())
				.startDate(request.startDate())
				.endDate(request.endDate())
				.build());
	}
}
