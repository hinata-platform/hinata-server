package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * MCP write tools for the sprint lifecycle (plan → start → complete). Gates on
 * {@code sprints:write} and delegates to {@link SprintService}, which enforces
 * board access (member of a spanned project), the SCRUM-board requirement for
 * creation and date sanity. Moving an issue in or out of a sprint is not here —
 * that is {@code update_issue}'s sprintId field ({@code issues:write}).
 */
@Service
@RequiredArgsConstructor
public class SprintWriteTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final SprintService sprintService;
	private final AuditService audit;

	@McpTool(name = "create_sprint", title = "Create sprint",
			annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false),
			description = "Plan a new sprint on a SCRUM board. The sprint is created in the "
					+ "planned state — it does not become the active sprint until started.")
	public BoardTools.SprintView create_sprint(
			@McpToolParam(required = true, description = "Id of the SCRUM board to plan the sprint on") String boardId,
			@McpToolParam(required = true, description = "Sprint name, e.g. 'Sprint 12'") String name,
			@McpToolParam(required = false, description = "Sprint goal") String goal,
			@McpToolParam(required = false, description = "Planned first day (yyyy-MM-dd)") LocalDate startDate,
			@McpToolParam(required = false, description = "Planned last day (yyyy-MM-dd)") LocalDate endDate,
			@McpToolParam(required = false, description = "Capacity in story points") Integer capacityPoints) {
		scopeGuard.require(Scopes.SPRINTS_WRITE);
		User me = currentUser.require();
		Sprint saved = sprintService.create(boardId, name, goal, startDate, endDate, capacityPoints, me);
		audit.event(AuditAction.MCP_SPRINT_CREATED).actor(me)
				.meta("sprint", saved.getId()).meta("board", boardId).log();
		return BoardTools.SprintView.of(saved);
	}

	@McpTool(name = "update_sprint", title = "Update sprint",
			annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false),
			description = "Update a sprint's name, goal, dates or capacity. Only the fields you "
					+ "pass are changed.")
	public BoardTools.SprintView update_sprint(
			@McpToolParam(required = true, description = "Sprint id") String sprintId,
			@McpToolParam(required = false, description = "New name") String name,
			@McpToolParam(required = false, description = "New goal") String goal,
			@McpToolParam(required = false, description = "New first day (yyyy-MM-dd)") LocalDate startDate,
			@McpToolParam(required = false, description = "New last day (yyyy-MM-dd)") LocalDate endDate,
			@McpToolParam(required = false, description = "New capacity in story points") Integer capacityPoints) {
		scopeGuard.require(Scopes.SPRINTS_WRITE);
		User me = currentUser.require();
		Sprint saved = sprintService.update(sprintId, name, goal, startDate, endDate, capacityPoints, me);
		audit.event(AuditAction.MCP_SPRINT_UPDATED).actor(me)
				.meta("sprint", saved.getId()).log();
		return BoardTools.SprintView.of(saved);
	}

	@McpTool(name = "start_sprint", title = "Start sprint",
			annotations = @McpTool.McpAnnotations(openWorldHint = false),
			description = "Start a sprint: locks its committed scope and makes it the board's "
					+ "active sprint. Optionally set the goal and end date at the same time.")
	public BoardTools.SprintView start_sprint(
			@McpToolParam(required = true, description = "Sprint id") String sprintId,
			@McpToolParam(required = false, description = "Sprint goal to set on start") String goal,
			@McpToolParam(required = false, description = "Last day of the sprint (yyyy-MM-dd)") LocalDate endDate) {
		scopeGuard.require(Scopes.SPRINTS_WRITE);
		User me = currentUser.require();
		Sprint saved = sprintService.start(sprintId, goal, endDate, me);
		audit.event(AuditAction.MCP_SPRINT_STARTED).actor(me)
				.meta("sprint", saved.getId()).log();
		return BoardTools.SprintView.of(saved);
	}

	@McpTool(name = "complete_sprint", title = "Complete sprint",
			annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false),
			description = "Complete (archive) a sprint. Every unfinished issue is moved to "
					+ "moveOpenTo: pass 'backlog' (or leave empty) to send them to the backlog, "
					+ "or a sibling sprint id on the same board.")
	public String complete_sprint(
			@McpToolParam(required = true, description = "Sprint id") String sprintId,
			@McpToolParam(required = false, description = "'backlog' (default) or the sprint id to move open issues to") String moveOpenTo) {
		scopeGuard.require(Scopes.SPRINTS_WRITE);
		User me = currentUser.require();
		sprintService.complete(sprintId, moveOpenTo, me);
		audit.event(AuditAction.MCP_SPRINT_COMPLETED).actor(me)
				.meta("sprint", sprintId).log();
		return "completed";
	}
}
