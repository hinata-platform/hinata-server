package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends a user-authored reply by e-mail to the original sender of an issue that
 * was created via email-to-ticket. Sending goes through the platform SMTP; the
 * Reply-To is set to the project's ingest mailbox so a customer reply loops back
 * into ingest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueEmailReplyService {

	/** Admin feature flag gating the whole reply-by-e-mail action. */
	public static final String FEATURE_FLAG = "emailReply";

	private final IssueService issues;
	private final IngestConnectionRepository connections;
	private final MailService mail;
	private final StorageService storage;
	private final SettingsService settings;
	private final HinataProperties properties;

	/**
	 * Validates access + preconditions and sends the reply. {@code attachmentIds}
	 * reference attachments already on the issue (uploaded through the normal
	 * attachment flow), so files are pulled from storage by object key.
	 */
	public void reply(String issueId, User user, String subject, String body,
			List<String> attachmentIds) {
		if (!featureEnabled()) {
			throw ApiException.forbidden("errors.emailReply.disabled");
		}
		if (subject == null || subject.isBlank()) {
			throw ApiException.badRequest("errors.emailReply.subjectRequired");
		}
		if (body == null || body.isBlank()) {
			throw ApiException.badRequest("errors.emailReply.bodyRequired");
		}

		// Authorize against the issue's project (throws 404/403 as appropriate).
		Issue issue = issues.getForUser(issueId, user);
		String recipient = issue.getReporterEmail();
		if (recipient == null || recipient.isBlank()) {
			throw ApiException.badRequest("errors.emailReply.notEmailSourced");
		}

		String replyTo = resolveReplyTo(issue);
		List<MailService.Attachment> files = collectAttachments(issue, attachmentIds);

		MailService.SendResult result = mail.sendReply(recipient, replyTo, subject,
				htmlBody(body), issue.getInboundMessageId(), files);
		switch (result) {
			case SENT -> log.info("E-mail reply for {} sent to {} by {}",
					issue.getReadableId(), recipient, user.getId());
			case NO_SMTP -> throw ApiException.conflict("errors.emailReply.noSmtp");
			case SEND_FAILED -> throw ApiException.badRequest("errors.emailReply.sendFailed");
		}
	}

	/**
	 * Mirrors the effective-flags merge in {@code MetaController#meta()} and
	 * {@code AdminSettingsController#get()}: env defaults first, admin DB override
	 * wins per-key. Must NOT use "DB wins entirely once non-empty" — that would
	 * make this gate disagree with what {@code /meta} told the app (which decides
	 * whether to show the reply button), rejecting requests the UI just offered.
	 */
	private boolean featureEnabled() {
		ServerSettings.App app = settings.get().getApp();
		Map<String, Boolean> flags = new java.util.LinkedHashMap<>(properties.getApp().getFeatureFlags());
		if (app.getFeatureFlags() != null) {
			flags.putAll(app.getFeatureFlags());
		}
		return Boolean.TRUE.equals(flags.get(FEATURE_FLAG));
	}

	/** The ingest mailbox that received the original mail, so replies loop back in. */
	private String resolveReplyTo(Issue issue) {
		if (issue.getIngestConnectionId() == null) {
			return null;
		}
		return connections.findById(issue.getIngestConnectionId())
				.map(IngestConnection::getUsername)
				.filter(u -> u != null && u.contains("@"))
				.orElse(null);
	}

	private List<MailService.Attachment> collectAttachments(Issue issue, List<String> attachmentIds) {
		List<MailService.Attachment> files = new ArrayList<>();
		if (attachmentIds == null || attachmentIds.isEmpty()) {
			return files;
		}
		for (String id : attachmentIds) {
			Issue.Attachment attachment = issue.getAttachments().stream()
					.filter(a -> a.getId().equals(id))
					.findFirst()
					.orElseThrow(() -> ApiException.notFound("attachment"));
			StorageService.StoredObject object = storage.getObject(attachment.getObjectKey())
					.orElseThrow(() -> ApiException.notFound("attachment"));
			String contentType = attachment.getContentType() != null
					? attachment.getContentType() : object.contentType();
			String fileName = attachment.getFileName() != null ? attachment.getFileName() : "attachment";
			files.add(new MailService.Attachment(fileName, contentType, object.data()));
		}
		return files;
	}

	/**
	 * Renders the composer's markdown-ish body to safe HTML: everything is escaped
	 * and newlines become {@code <br>}, wrapped in a design-system container. This
	 * avoids a markdown dependency while keeping the mail readable and injection-safe.
	 */
	private String htmlBody(String body) {
		String safe = HtmlUtils.htmlEscape(body).replace("\n", "<br>");
		return """
				<div style="font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F2F1F8;padding:32px">
				  <div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:24px;padding:32px">
				    <p style="color:#4A4866;font-size:15px;line-height:1.6;margin:0">%s</p>
				  </div>
				</div>
				""".formatted(safe);
	}
}
