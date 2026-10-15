package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.IssueParticipantService;
import com.ahmadre.hinata.pat.Scopes;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tools for sharing an issue with somebody outside its project (HIN-136). Each gates on
 * {@code issues:write} and hands the rest to {@link IssueParticipantService}: only project members
 * share, a participant may only remove themselves, at most
 * {@link IssueParticipantService#MAX_PARTICIPANTS} per issue. The service writes the history and
 * the audit entries.
 */
@Service
@RequiredArgsConstructor
public class IssueParticipantTools {

	private final IssueParticipantService participants;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;

	@McpTool(name = "add_participant", title = "Share an issue with a person",
			annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false),
			description = "Share one issue with a person outside its project. They can read and comment on "
					+ "this issue only and are notified once. Idempotent. Only project members may share.")
	public String add_participant(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42)") String idOrReadableId,
			@McpToolParam(required = true, description = "User id of the person to share with") String userId) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		participants.add(idOrReadableId, userId, currentUser.require());
		return "shared";
	}

	@McpTool(name = "remove_participant", title = "Stop sharing an issue with a person",
			annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false),
			description = "Take a participant off an issue. Project members may remove anybody; "
					+ "a participant may remove only themselves.")
	public String remove_participant(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42)") String idOrReadableId,
			@McpToolParam(required = true, description = "User id of the participant") String userId) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		participants.remove(idOrReadableId, userId, currentUser.require());
		return "removed";
	}
}
