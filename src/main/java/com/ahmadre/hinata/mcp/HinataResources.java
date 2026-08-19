package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.LexicalToMarkdown;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.user.User;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

/**
 * MCP resources exposing hinata entities under stable {@code hinata://} URIs so a
 * client can reference an issue, project or article by hand. Each gates on the
 * same capability scope as the tool that returns the same entity, resolves the
 * caller via {@link CurrentUser} and delegates through the owning service — a
 * resource read is another way into the same data, and a scoped token must not
 * find here what it was refused there. Each returns rendered
 * {@code text/markdown} — a String return is wrapped by the framework into a
 * text resource with the declared mime type. The attachment
 * resource is the exception: it builds its own {@link ReadResourceResult} so a
 * picture can travel as a binary blob rather than as prose about a picture.
 */
@Component
@RequiredArgsConstructor
public class HinataResources {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final IssueService issueService;
	private final AttachmentReader attachments;
	private final ProjectService projectService;
	private final ProjectRepository projects;
	private final KnowledgeReadTools knowledge;

	@McpResource(name = "issue", uri = "hinata://issue/{readableId}",
			description = "An issue rendered as markdown, by readable id (e.g. ASTA-42) or id.",
			mimeType = "text/markdown")
	public String issue(String readableId) {
		scopeGuard.require(Scopes.ISSUES_READ);
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
		// The body is rendered back to markdown from the stored document — the
		// resource declares text/markdown, and the derived plain-text field is not
		// markdown, it is the projection of it.
		String body = LexicalToMarkdown.fromStored(issue.getDescriptionDoc(), issue.getDescription());
		if (body != null && !body.isBlank()) {
			md.append(body).append("\n");
		}
		return md.toString();
	}

	/**
	 * One attachment of one issue, addressable so it can be pinned into a
	 * conversation by hand instead of fetched through a tool call. Same bounds and
	 * same ACL as {@code get_attachment} — {@link AttachmentReader} is the only
	 * path to an attachment's bytes, and the declared mime type below is only the
	 * advertised default: each read answers with the type it actually produced.
	 */
	@McpResource(name = "issue-attachment",
			uri = "hinata://issue/{readableId}/attachment/{attachmentId}",
			description = "The content of one issue attachment — images as a binary blob "
					+ "(downscaled), PDFs and text files as extracted text (length-capped). "
					+ "Ids come from the list_attachments tool.",
			mimeType = "application/octet-stream")
	public ReadResourceResult attachment(String readableId, String attachmentId) {
		AttachmentReader.Rendered rendered = attachments.read(readableId, attachmentId, null);
		String uri = "hinata://issue/" + readableId + "/attachment/" + attachmentId;
		return new ReadResourceResult(contents(uri, rendered));
	}

	private static List<ResourceContents> contents(String uri, AttachmentReader.Rendered rendered) {
		// The description travels with the bytes on this surface too. A picture
		// pasted into a ticket can carry writing, and writing a model reads is
		// writing that can try to instruct it — the resource read has no less need
		// of the "this is untrusted data" framing than the tool call does.
		String description = AttachmentSummary.describe(
				rendered.issue(), rendered.attachment(), rendered.content());
		TextResourceContents prose = new TextResourceContents(uri, "text/plain", description);
		return switch (rendered.content()) {
			case AttachmentContent.Image image -> List.of(prose,
					new BlobResourceContents(uri, image.contentType(),
							Base64.getEncoder().encodeToString(image.bytes())));
			case AttachmentContent.Text extract -> List.of(new TextResourceContents(uri, "text/plain",
					description + "\n\n" + extract.text()));
			case AttachmentContent.Unavailable ignored -> List.of(prose);
		};
	}

	@McpResource(name = "project", uri = "hinata://project/{key}",
			description = "A project rendered as markdown, by key (e.g. ASTA) or id.",
			mimeType = "text/markdown")
	public String project(String key) {
		scopeGuard.require(Scopes.PROJECTS_READ);
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
		scopeGuard.require(Scopes.KB_READ);
		User user = currentUser.require();
		Article article = knowledge.requireVisible(id, user);
		StringBuilder md = new StringBuilder();
		md.append("# ").append(nz(article.getTitle())).append("\n\n");
		String body = LexicalToMarkdown.fromStored(article.getContentDoc(), article.getContent());
		if (body != null && !body.isEmpty()) {
			md.append(body).append("\n");
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
