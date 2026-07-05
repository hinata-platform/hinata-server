package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * MCP resources exposing hinata entities under stable {@code hinata://} URIs so a
 * client can reference an issue, project or article by hand. Each resolves the
 * caller via {@link CurrentUser} and delegates through the owning service, so the
 * same project / knowledge-base ACLs that guard the tools apply here too. Each
 * returns rendered {@code text/markdown} — a String return is wrapped by the
 * framework into a text resource with the declared mime type.
 */
@Component
@RequiredArgsConstructor
public class HinataResources {

	private final CurrentUser currentUser;
	private final IssueService issueService;
	private final ProjectService projectService;
	private final ProjectRepository projects;
	private final KnowledgeReadTools knowledge;

	@McpResource(name = "issue", uri = "hinata://issue/{readableId}",
			description = "An issue rendered as markdown, by readable id (e.g. ASTA-42) or id.",
			mimeType = "text/markdown")
	public String issue(String readableId) {
		User user = currentUser.require();
		Issue issue = issueService.getForUser(readableId, user);
		StringBuilder md = new StringBuilder();
		md.append("# ").append(nz(issue.getReadableId())).append(" — ").append(nz(issue.getTitle())).append("\n\n");
		md.append("- Type: ").append(name(issue.getType())).append("\n");
		md.append("- State: ").append(nz(issue.getState())).append("\n");
		md.append("- Priority: ").append(name(issue.getPriority())).append("\n");
		if (issue.getAssigneeId() != null) md.append("- Assignee: ").append(issue.getAssigneeId()).append("\n");
		if (issue.getSprintId() != null) md.append("- Sprint: ").append(issue.getSprintId()).append("\n");
		if (issue.getDueDate() != null) md.append("- Due: ").append(issue.getDueDate()).append("\n");
		md.append("\n");
		if (issue.getDescription() != null && !issue.getDescription().isBlank()) {
			md.append(issue.getDescription()).append("\n");
		}
		return md.toString();
	}

	@McpResource(name = "project", uri = "hinata://project/{key}",
			description = "A project rendered as markdown, by key (e.g. ASTA) or id.",
			mimeType = "text/markdown")
	public String project(String key) {
		User user = currentUser.require();
		Project project = projects.findById(key)
				.or(() -> projects.findByKeyIgnoreCase(key))
				.orElseThrow(() -> ApiException.notFound("project"));
		projectService.assertMember(project, user);
		StringBuilder md = new StringBuilder();
		md.append("# ").append(nz(project.getKey())).append(" — ").append(nz(project.getName())).append("\n\n");
		if (project.getDescription() != null && !project.getDescription().isBlank()) {
			md.append(project.getDescription()).append("\n\n");
		}
		md.append("- Members: ")
				.append(project.getMemberIds() == null ? 0 : project.getMemberIds().size()).append("\n");
		md.append("- Workflow: ").append(String.join(" → ", project.workflowStateNames())).append("\n");
		if (!project.labelNames().isEmpty()) {
			md.append("- Labels: ").append(String.join(", ", project.labelNames())).append("\n");
		}
		md.append("- Archived: ").append(project.isArchived()).append("\n");
		return md.toString();
	}

	@McpResource(name = "kb-article", uri = "hinata://kb/{id}",
			description = "A knowledge base article's markdown content, by id.",
			mimeType = "text/markdown")
	public String article(String id) {
		User user = currentUser.require();
		Article article = knowledge.requireVisible(id, user);
		StringBuilder md = new StringBuilder();
		md.append("# ").append(nz(article.getTitle())).append("\n\n");
		if (article.getContent() != null) {
			md.append(article.getContent()).append("\n");
		}
		return md.toString();
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}

	private static String name(Enum<?> value) {
		return value == null ? "—" : value.name();
	}
}
