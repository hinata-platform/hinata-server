package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * MCP write tools for issues. Each tool gates on the {@code issues:write} scope,
 * resolves the authenticated user, then delegates to {@link IssueService} so all
 * project-membership ACLs, state/tag validation and hierarchy rules are enforced
 * server-side exactly as they are for the REST controller. Successful writes are
 * recorded in the audit log and returned as a lean {@link McpViews.IssueView}.
 */
@Service
@RequiredArgsConstructor
public class IssueWriteTools {

	private final IssueService issueService;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final AuditService audit;

	@McpTool(name = "create_issue", title = "Create issue",
			description = "Create a new issue in a project. Requires the project id and a title; "
					+ "all other fields are optional. Returns the created issue.")
	public McpViews.IssueView create_issue(
			@McpToolParam(required = true, description = "Id of the project to create the issue in") String projectId,
			@McpToolParam(required = true, description = "Short summary / title of the issue") String title,
			@McpToolParam(required = false, description = "Markdown description / body") String description,
			@McpToolParam(required = false, description = "Issue type (EPIC, STORY, TASK, BUG, FEATURE, SUBTASK); defaults to TASK") Issue.Type type,
			@McpToolParam(required = false, description = "Priority (SHOWSTOPPER, CRITICAL, MAJOR, NORMAL, MINOR); defaults to NORMAL") Issue.Priority priority,
			@McpToolParam(required = false, description = "Workflow state name; defaults to the project's first state") String state,
			@McpToolParam(required = false, description = "User ids to assign the issue to") List<String> assigneeIds,
			@McpToolParam(required = false, description = "Parent issue id (epic for a standard issue, standard issue for a sub-task)") String parentId,
			@McpToolParam(required = false, description = "Sprint id to place the issue in") String sprintId,
			@McpToolParam(required = false, description = "Labels / tags") List<String> tags,
			@McpToolParam(required = false, description = "Planned start date (yyyy-MM-dd)") LocalDate startDate,
			@McpToolParam(required = false, description = "Due date (yyyy-MM-dd)") LocalDate dueDate,
			@McpToolParam(required = false, description = "Original time estimate in minutes") Integer estimateMinutes,
			@McpToolParam(required = false, description = "Story point estimate") Integer storyPoints) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		User me = currentUser.require();
		Issue issue = Issue.builder()
				.projectId(projectId)
				.title(title)
				.description(description)
				.type(type != null ? type : Issue.Type.TASK)
				.priority(priority != null ? priority : Issue.Priority.NORMAL)
				.state(state)
				.assigneeIds(assigneeIds != null ? assigneeIds : List.of())
				.parentId(parentId)
				.sprintId(sprintId)
				.tags(tags != null ? tags : List.of())
				.startDate(startDate)
				.dueDate(dueDate)
				.estimateMinutes(estimateMinutes)
				.storyPoints(storyPoints)
				.build();
		Issue saved = issueService.create(issue, me);
		audit.event(AuditAction.MCP_ISSUE_CREATED).actor(me)
				.meta("issue", saved.getReadableId()).log();
		return McpViews.IssueView.of(saved);
	}

	@McpTool(name = "update_issue", title = "Update issue",
			description = "Update fields on an existing issue. Only the fields you pass are changed; "
					+ "leaving a field unset means \"no change\". To clear a start date, due date or story "
					+ "points, set the matching clear* flag to true (passing null never clears a value). "
					+ "Returns the updated issue.")
	public McpViews.IssueView update_issue(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42)") String idOrReadableId,
			@McpToolParam(required = false, description = "New title") String title,
			@McpToolParam(required = false, description = "New markdown description / body") String description,
			@McpToolParam(required = false, description = "New issue type (EPIC, STORY, TASK, BUG, FEATURE, SUBTASK)") Issue.Type type,
			@McpToolParam(required = false, description = "New priority (SHOWSTOPPER, CRITICAL, MAJOR, NORMAL, MINOR)") Issue.Priority priority,
			@McpToolParam(required = false, description = "New workflow state name") String state,
			@McpToolParam(required = false, description = "Replacement list of assignee user ids") List<String> assigneeIds,
			@McpToolParam(required = false, description = "New parent issue id; pass an empty string to detach from the parent") String parentId,
			@McpToolParam(required = false, description = "New sprint id; pass an empty string to move to the backlog") String sprintId,
			@McpToolParam(required = false, description = "Replacement list of labels / tags") List<String> tags,
			@McpToolParam(required = false, description = "Replacement list of issue ids this issue depends on") List<String> dependsOnIds,
			@McpToolParam(required = false, description = "New planned start date (yyyy-MM-dd)") LocalDate startDate,
			@McpToolParam(required = false, description = "New due date (yyyy-MM-dd)") LocalDate dueDate,
			@McpToolParam(required = false, description = "New original time estimate in minutes") Integer estimateMinutes,
			@McpToolParam(required = false, description = "New story point estimate") Integer storyPoints,
			@McpToolParam(required = false, description = "Set true to clear the start date") Boolean clearStartDate,
			@McpToolParam(required = false, description = "Set true to clear the due date") Boolean clearDueDate,
			@McpToolParam(required = false, description = "Set true to clear the story points") Boolean clearStoryPoints) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		User me = currentUser.require();
		Issue saved = issueService.update(idOrReadableId, issue -> {
			if (title != null) issue.setTitle(title);
			if (description != null) issue.setDescription(description);
			if (type != null) issue.setType(type);
			if (priority != null) issue.setPriority(priority);
			if (state != null) issue.setState(state);
			if (assigneeIds != null) issue.setAssigneeIds(assigneeIds);
			if (parentId != null) {
				issue.setParentId(parentId.isBlank() ? null : parentId);
			}
			if (sprintId != null) {
				issue.setSprintId(sprintId.isBlank() ? null : sprintId);
			}
			if (tags != null) issue.setTags(tags);
			if (dependsOnIds != null) issue.setDependsOnIds(dependsOnIds);
			if (Boolean.TRUE.equals(clearStartDate)) {
				issue.setStartDate(null);
			} else if (startDate != null) {
				issue.setStartDate(startDate);
			}
			if (Boolean.TRUE.equals(clearDueDate)) {
				issue.setDueDate(null);
			} else if (dueDate != null) {
				issue.setDueDate(dueDate);
			}
			if (estimateMinutes != null) issue.setEstimateMinutes(estimateMinutes);
			if (Boolean.TRUE.equals(clearStoryPoints)) {
				issue.setStoryPoints(null);
			} else if (storyPoints != null) {
				issue.setStoryPoints(storyPoints);
			}
		}, me);
		audit.event(AuditAction.MCP_ISSUE_UPDATED).actor(me)
				.meta("issue", saved.getReadableId()).log();
		return McpViews.IssueView.of(saved);
	}

	@McpTool(name = "add_comment", title = "Add comment",
			description = "Add a comment to an issue. Returns the created comment.")
	public McpViews.CommentView add_comment(
			@McpToolParam(required = true, description = "Issue id or readable id (e.g. HIN-42)") String idOrReadableId,
			@McpToolParam(required = true, description = "Comment text (markdown)") String text) {
		scopeGuard.require(Scopes.ISSUES_WRITE);
		User me = currentUser.require();
		IssueComment saved = issueService.addComment(idOrReadableId, text, me);
		audit.event(AuditAction.MCP_COMMENT_ADDED).actor(me)
				.meta("issue", saved.getIssueId()).log();
		return McpViews.CommentView.of(saved);
	}
}
