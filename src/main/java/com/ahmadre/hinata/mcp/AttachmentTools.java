package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.user.User;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * MCP tools over an issue's file attachments: what is attached, and what is
 * <em>in</em> it. A large share of tickets carry their real content in a
 * screenshot rather than in their text, and a listing of file names leaves an
 * agent working blind on exactly those.
 *
 * <p>Both tools gate on {@code issues:read} and resolve the issue through
 * {@link IssueService}, so the project ACL that guards the app guards these too.
 * Reading content additionally runs through {@link AttachmentReader}, which owns
 * the rate budget, the audit record and the bounds every response is held to.
 */
@Service
@RequiredArgsConstructor
public class AttachmentTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final IssueService issueService;
	private final AttachmentReader reader;

	@McpTool(name = "list_attachments", title = "List attachments",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the file attachments of an issue — name, type, size, uploader. "
					+ "Metadata only; call get_attachment with an id from this list to read what "
					+ "is actually in the file (images come back as images, PDFs and text as text).")
	public List<AttachmentView> listAttachments(
			@McpToolParam(description = "Issue id or readable id (e.g. ASTA-42)") String idOrReadableId) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		Issue issue = issueService.getForUser(idOrReadableId, user);
		return issue.getAttachments() == null ? List.of()
				: issue.getAttachments().stream().map(AttachmentView::of).toList();
	}

	/** Attachment metadata without the internal storage object key. */
	public record AttachmentView(String id, String fileName, String contentType, long size,
			String uploaderId, Instant uploadedAt) {

		static AttachmentView of(Issue.Attachment a) {
			return new AttachmentView(a.getId(), a.getFileName(), a.getContentType(), a.getSize(),
					a.getUploaderId(), a.getUploadedAt());
		}
	}

	@McpTool(name = "get_attachment", title = "Get an attachment",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Read the content of one issue attachment. Images are returned as image "
					+ "content, downscaled; PDFs and text files as extracted text, length-capped. "
					+ "Other types, over-sized files and undecodable payloads return metadata and a "
					+ "plain explanation instead of an error. The attachment id comes from "
					+ "list_attachments and must belong to the issue you name.")
	public CallToolResult getAttachment(
			@McpToolParam(description = "Issue id or readable id (e.g. ASTA-42)") String idOrReadableId,
			@McpToolParam(description = "Attachment id from list_attachments") String attachmentId,
			@McpToolParam(required = false,
					description = "Max width in px for images (default 1600, clamped by the server)")
					Integer maxWidth) {
		AttachmentReader.Rendered rendered = reader.read(idOrReadableId, attachmentId, maxWidth);
		return toResult(rendered);
	}

	/**
	 * Maps one rendered attachment onto MCP content. Never {@code isError}: a ZIP
	 * that cannot be shown is a fact about the file, and an agent that receives it
	 * as an error tends to retry rather than move on.
	 */
	private static CallToolResult toResult(AttachmentReader.Rendered rendered) {
		AttachmentContent content = rendered.content();
		CallToolResult.Builder result = CallToolResult.builder()
				.addTextContent(AttachmentSummary.describe(
						rendered.issue(), rendered.attachment(), content));
		switch (content) {
			case AttachmentContent.Image image -> result.addContent(new ImageContent(null,
					Base64.getEncoder().encodeToString(image.bytes()), image.contentType()));
			case AttachmentContent.Text extract -> result.addTextContent(extract.text());
			case AttachmentContent.Unavailable ignored -> {
				// The description already carries the reason; nothing to attach.
			}
		}
		return result.build();
	}
}
