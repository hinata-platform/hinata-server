package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * A sprint's time budget and what it has used (HIN-156): entries on the sprint's issues within its
 * dates. Anyone who sees the board reads it; a lead of one of the board's projects sets it, as
 * they would a project's budget. Gated with the extended module.
 */
@Tag(name = "Time Tracking")
@RestController
@RequiredArgsConstructor
public class SprintTimeController {

	private final SprintService sprints;
	private final TimeHistoryService history;
	private final MongoTemplate mongo;
	private final CurrentUser currentUser;
	private final AuditService audit;
	private final Clock clock;

	/** Null budget removes it. */
	public record SprintBudgetRequest(
			@Min(value = 0, message = "error.time.budgetInvalid")
			@Max(value = ProjectTimeSettings.MAX_BUDGET_MINUTES, message = "error.time.budgetInvalid")
			Integer budgetMinutes) {
	}

	@GetMapping("/api/v1/sprints/{sprintId}/time-usage")
	public TimeHistoryService.SprintUsage usage(@PathVariable String sprintId) {
		User user = currentUser.require();
		Sprint sprint = sprints.accessibleSprint(sprintId, user);
		AgileBoard board = sprints.accessibleBoard(sprint.getBoardId(), user);
		// Summed over the board's projects the reader sees; whether the reader may set it, so a client
		// offers the way only to those who can.
		return history.sprintUsage(sprint, sprints.visibleProjectIds(board, user))
				.editable(sprints.canManage(board, user));
	}

	@PutMapping("/api/v1/sprints/{sprintId}/time-settings")
	public TimeHistoryService.SprintUsage put(@PathVariable String sprintId,
			@RequestBody @Valid SprintBudgetRequest request) {
		User user = currentUser.require();
		Sprint sprint = sprints.accessibleSprint(sprintId, user);
		AgileBoard board = sprints.accessibleBoard(sprint.getBoardId(), user);
		if (!sprints.canManage(board, user)) {
			throw ApiException.forbidden("error.board.notManager");
		}
		Integer budget = request.budgetMinutes() == null || request.budgetMinutes() == 0 ? null
				: request.budgetMinutes();
		SprintTimeSettings before = mongo.findOne(Query.query(Criteria.where("sprintId").is(sprint.getId())),
				SprintTimeSettings.class);
		mongo.upsert(Query.query(Criteria.where("sprintId").is(sprint.getId())),
				new Update().set("budgetMinutes", budget).set("updatedAt", clock.instant())
						.set("updatedBy", user.getId()),
				SprintTimeSettings.class);
		audit.event(AuditAction.TIME_SPRINT_SETTINGS_CHANGED).actor(user)
				.target(sprint.getId(), sprint.getName())
				.meta("sprint", sprint.getId())
				.meta("board", board.getId())
				.meta("projects", String.join(",", board.getProjectIds()))
				.meta("budgetMinutes", (before == null ? null : before.getBudgetMinutes()) + " → " + budget)
				.log();
		return history.sprintUsage(sprint, sprints.visibleProjectIds(board, user)).editable(true);
	}
}
