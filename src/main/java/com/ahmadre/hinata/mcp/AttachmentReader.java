package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.storage.AttachmentContentService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The single path from "an id an agent typed" to "bytes it may see". Both MCP
 * surfaces that expose attachment content — the {@code get_attachment} tool and
 * the {@code hinata://issue/…/attachment/…} resource — go through here, so the
 * guarantees hold for either entry point rather than for whichever one was
 * written first.
 *
 * <p>In order: the {@code issues:read} scope, the caller's identity, the caller's
 * own rate budget, then the issue resolved through {@link IssueService} so the
 * project ACL decides. Only <em>after</em> that is the attachment looked up, and
 * only inside the list of the issue that was authorized — which is what makes
 * one issue's attachment id useless against another issue (IDOR). Every read is
 * audited, whatever it returned.
 */
@Component
@RequiredArgsConstructor
public class AttachmentReader {

	/** One authorized read: the attachment's metadata and its rendered content. */
	public record Rendered(Issue issue, Issue.Attachment attachment, AttachmentContent content) {
	}

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final IssueService issueService;
	private final AttachmentContentService contents;
	private final AttachmentReadLimiter limiter;
	private final AuditService audit;
	private final HinataProperties properties;

	/**
	 * Reads one attachment of one issue. [maxWidth] is the caller's wish for
	 * images and may be null; the render clamps it either way.
	 */
	public Rendered read(String idOrReadableId, String attachmentId, Integer maxWidth) {
		scopeGuard.require(Scopes.ISSUES_READ);
		User user = currentUser.require();
		// Metered before any database or storage work: probing ids is exactly the
		// traffic worth metering, and it must cost the prober, not the server.
		limiter.require(user.getId());
		Issue issue = issueService.getForUser(idOrReadableId, user);
		Issue.Attachment attachment = find(issue.getAttachments(), attachmentId);
		AttachmentContent content = contents.render(attachment, limits(maxWidth));
		audit.event(AuditAction.MCP_ATTACHMENT_READ).actor(user)
				.meta("issue", issue.getReadableId())
				.meta("attachment", attachment.getId())
				.meta("file", attachment.getFileName())
				.meta("result", describe(content))
				.log();
		return new Rendered(issue, attachment, content);
	}

	/**
	 * The attachment with that id <em>on this issue</em>. A miss is a 404 and not
	 * a 403: the caller has already been proven a member of this project, so
	 * "there is no such attachment here" tells them nothing they could not
	 * discover with {@code list_attachments}.
	 */
	private static Issue.Attachment find(List<Issue.Attachment> attachments, String attachmentId) {
		if (attachments == null || attachmentId == null) {
			throw ApiException.notFound("attachment");
		}
		return attachments.stream()
				.filter(a -> attachmentId.equals(a.getId()))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("attachment"));
	}

	private AttachmentContentService.Limits limits(Integer maxWidth) {
		HinataProperties.Mcp mcp = properties.getMcp();
		return new AttachmentContentService.Limits(
				(long) mcp.getAttachmentMaxMb() * 1024 * 1024,
				maxWidth != null ? maxWidth : mcp.getAttachmentImageWidth(),
				mcp.getAttachmentTextChars());
	}

	/** A short, non-sensitive summary of the outcome for the audit trail. */
	private static String describe(AttachmentContent content) {
		return switch (content) {
			case AttachmentContent.Image image -> "image " + image.width() + "x" + image.height();
			case AttachmentContent.Text text -> text.truncated() ? "text (truncated)" : "text";
			case AttachmentContent.Unavailable unavailable -> unavailable.reason().name().toLowerCase();
		};
	}
}
