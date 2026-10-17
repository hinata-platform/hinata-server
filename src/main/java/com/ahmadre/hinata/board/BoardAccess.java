package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who may read a board: whoever may see at least one of its projects. The paged reads and the
 * board's own routes ask here, so the rule is one.
 */
@Component
@RequiredArgsConstructor
class BoardAccess {

	private final ProjectService projects;

	/** The board's projects [user] may see, active ones only, in the board's order. */
	List<Project> readableProjects(AgileBoard board, User user) {
		List<String> ids = board.getProjectIds() == null ? List.of()
				: board.getProjectIds().stream().filter(Objects::nonNull).distinct().toList();
		Map<String, Project> readable = projects.visibleAmong(user, ids).stream()
				.filter(project -> !project.isArchived())
				.collect(Collectors.toMap(Project::getId, Function.identity(), (first, second) -> first));
		return ids.stream().map(readable::get).filter(Objects::nonNull).toList();
	}

	/** The ids of the board's projects [user] may see, archived ones included. */
	java.util.Set<String> visibleProjectIds(AgileBoard board, User user) {
		List<String> ids = board.getProjectIds() == null ? List.of() : board.getProjectIds();
		return projects.visibleAmong(user, ids).stream().map(Project::getId).collect(Collectors.toSet());
	}

	/**
	 * Who may manage a board (rename, delete, its sprint budget): its owner, a lead of any project it
	 * spans, a team administrator of a team owning one. Members use a board but do not reconfigure
	 * it. The board's projects in one read.
	 */
	boolean canManage(AgileBoard board, User user) {
		if (user.getId().equals(board.getOwnerId())) {
			return true;
		}
		List<String> ids = board.getProjectIds() == null ? List.of() : board.getProjectIds();
		return projects.visibleAmong(user, ids).stream().anyMatch(project -> projects.canManage(project, user));
	}

	/**
	 * The board's projects [user] may see.
	 *
	 * @throws ApiException 403 when the viewer may see none of them
	 */
	List<Project> assertReadable(AgileBoard board, User user) {
		List<Project> readable = readableProjects(board, user);
		if (readable.isEmpty()) {
			throw ApiException.forbidden("error.accessDenied");
		}
		return readable;
	}
}
