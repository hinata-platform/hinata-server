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
 * Who may read a board: whoever may see at least one of its projects, and an admin, who reads a board
 * of projects nobody may see as empty. The paged reads and the board's own routes ask here, so the
 * rule is one.
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

	/**
	 * The board's projects [user] may see.
	 *
	 * @throws ApiException 403 when the viewer may see none of them and is no admin
	 */
	List<Project> assertReadable(AgileBoard board, User user) {
		List<Project> readable = readableProjects(board, user);
		if (readable.isEmpty() && !user.isAdmin()) {
			throw ApiException.forbidden("error.accessDenied");
		}
		return readable;
	}
}
