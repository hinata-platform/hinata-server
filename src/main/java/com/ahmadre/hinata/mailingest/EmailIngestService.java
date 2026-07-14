package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E-mail-to-ticket: polls every enabled managed IMAP connection and turns
 * unseen messages into issues in each connection's linked project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailIngestService {

	private final IngestConnectionRepository connections;
	private final IssueService issues;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final StorageService storage;
	private final AttachmentStore attachments;

	/** Per-connection epoch seconds of the last poll (the 15s tick is the beat). */
	private final Map<String, Long> lastRun = new ConcurrentHashMap<>();

	@Scheduled(fixedDelay = 15000)
	public void poll() {
		long now = Instant.now().getEpochSecond();
		for (IngestConnection config : connections.findByEnabledTrue()) {
			if (config.getHost() == null || config.getProjectId() == null) {
				continue;
			}
			long last = lastRun.getOrDefault(config.getId(), 0L);
			if (now - last < config.getPollSeconds()) {
				continue;
			}
			lastRun.put(config.getId(), now);
			try {
				ingest(config);
			}
			catch (Exception ex) {
				log.warn("E-mail ingestion for {}@{} failed: {}",
						config.getUsername(), config.getHost(), ex.getMessage());
			}
		}
	}

	private void ingest(IngestConnection config) throws Exception {
		Properties props = new Properties();
		String protocol = config.isSsl() ? "imaps" : "imap";
		props.put("mail.store.protocol", protocol);
		props.put("mail." + protocol + ".host", config.getHost());
		props.put("mail." + protocol + ".port", String.valueOf(config.getPort()));
		props.put("mail." + protocol + ".connectiontimeout", "10000");
		props.put("mail." + protocol + ".timeout", "15000");

		Session session = Session.getInstance(props);
		try (Store store = session.getStore(protocol)) {
			store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
			Folder folder = store.getFolder(config.getFolder());
			folder.open(Folder.READ_WRITE);
			try {
				for (Message message : folder.search(
						new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false))) {
					createIssueFrom(message, config);
					message.setFlag(Flags.Flag.SEEN, true);
				}
			}
			finally {
				folder.close(false);
			}
		}
	}

	/**
	 * Prefix every ingested description carries. Also the guard the reprocess repair
	 * uses to avoid overwriting a description a human has since edited.
	 */
	private static final String DESCRIPTION_HEADER = "Created from e-mail by ";

	private void createIssueFrom(Message message, IngestConnection config) throws Exception {
		String projectId = config.getProjectId();
		String subject = message.getSubject() != null ? message.getSubject() : "(no subject)";
		String from = senderOf(message);
		Issue issue = Issue.builder()
				.projectId(projectId)
				.title(truncate(subject, 300))
				.description(buildDescription(from, message))
				.type(Issue.Type.TASK)
				.reporterEmail(from)
				.inboundMessageId(messageIdOf(message))
				.inboundSubject(truncate(subject, 300))
				.ingestConnectionId(config.getId())
				.build();
		Issue created = issues.create(issue, null);
		log.info("Created {} from e-mail by {}", created.getReadableId(), from);
		notifyMembers(created, projectId, from);
		attachFiles(message, created.getId());
	}

	/** The description body written for an ingested message: an attribution header
	 * plus the parsed (plain or HTML→Markdown) mail body. */
	private String buildDescription(String from, Message message) throws Exception {
		return DESCRIPTION_HEADER + "**" + from + "**\n\n---\n\n" + truncate(textOf(message), 20000);
	}

	private String senderOf(Message message) throws Exception {
		return message.getFrom() != null && message.getFrom().length > 0
				? ((InternetAddress) message.getFrom()[0]).getAddress()
				: "unknown";
	}

	private String messageIdOf(Message message) throws Exception {
		String[] ids = message.getHeader("Message-ID");
		return ids != null && ids.length > 0 ? ids[0] : null;
	}

	/**
	 * Re-reads a connection's mailbox (read-only — seen flags are left untouched) and
	 * rebuilds the description of every ticket whose source message is still present,
	 * using the current body parser. This is the repair path for tickets ingested
	 * while HTML handling was still broken. Only auto-generated descriptions are
	 * rewritten, so manual edits survive, and no new tickets are ever created. A
	 * single unreadable message is logged and skipped, never aborting the run.
	 *
	 * @return how many messages were scanned and how many ticket descriptions changed
	 */
	public ReprocessResult reprocess(IngestConnection config) {
		Properties props = new Properties();
		String protocol = config.isSsl() ? "imaps" : "imap";
		props.put("mail.store.protocol", protocol);
		props.put("mail." + protocol + ".host", config.getHost());
		props.put("mail." + protocol + ".port", String.valueOf(config.getPort()));
		props.put("mail." + protocol + ".connectiontimeout", "10000");
		props.put("mail." + protocol + ".timeout", "15000");
		Session session = Session.getInstance(props);
		int scanned = 0;
		int updated = 0;
		try (Store store = session.getStore(protocol)) {
			store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
			Folder folder = store.getFolder(config.getFolder());
			folder.open(Folder.READ_ONLY);
			try {
				for (Message message : folder.getMessages()) {
					scanned++;
					if (rebuildFrom(message, config)) {
						updated++;
					}
				}
			}
			finally {
				folder.close(false);
			}
		}
		catch (Exception ex) {
			log.info("IMAP reprocess for {}@{} failed: {}",
					config.getUsername(), config.getHost(), ex.getMessage());
			throw ApiException.badRequest("error.ingest.connectionFailed", ex.getMessage());
		}
		log.info("Reprocessed mailbox {}@{}: rebuilt {}/{} ticket description(s)",
				config.getUsername(), config.getHost(), updated, scanned);
		return new ReprocessResult(scanned, updated);
	}

	/**
	 * Rebuilds a single ticket from its source message when it still exists, belongs
	 * to this connection's project, and its description is still the auto-generated
	 * one. Returns whether the description actually changed. Best-effort: a broken
	 * message is skipped, not fatal.
	 */
	private boolean rebuildFrom(Message message, IngestConnection config) {
		try {
			String messageId = messageIdOf(message);
			if (messageId == null) {
				return false;
			}
			Issue issue = issues.findByInboundMessageId(messageId).orElse(null);
			if (issue == null
					|| !config.getProjectId().equals(issue.getProjectId())
					|| issue.getDescription() == null
					|| !issue.getDescription().startsWith(DESCRIPTION_HEADER)) {
				return false;
			}
			String rebuilt = buildDescription(senderOf(message), message);
			if (rebuilt.equals(issue.getDescription())) {
				return false; // already current — nothing to write
			}
			issues.replaceIngestedDescription(issue.getId(), rebuilt);
			return true;
		}
		catch (Exception ex) {
			log.warn("Reprocessing a message in folder {} failed: {}",
					config.getFolder(), ex.getMessage());
			return false;
		}
	}

	/** Outcome of a {@link #reprocess} run. */
	public record ReprocessResult(int scanned, int updated) {
	}

	/**
	 * Tells the project's members that an issue arrived by e-mail. Best-effort:
	 * a lookup or delivery failure is logged and never aborts ticket creation.
	 */
	private void notifyMembers(Issue created, String projectId, String from) {
		try {
			Project project = projects.get(projectId);
			notifications.notifyIssueIngested(created, project.getMemberIds(), from);
		}
		catch (Exception ex) {
			log.warn("Notifying members of ingested issue {} failed: {}",
					created.getReadableId(), ex.getMessage());
		}
	}

	/**
	 * Walks the message's MIME tree and stores every attachment part as an issue
	 * attachment. Runs after the issue exists so a single bad part can never abort
	 * ticket creation; failures are logged and skipped.
	 */
	private void attachFiles(Message message, String issueId) {
		if (!storage.isConfigured()) {
			return;
		}
		try {
			attachFrom(message, issueId);
		}
		catch (Exception ex) {
			log.warn("Extracting e-mail attachments for {} failed: {}", issueId, ex.getMessage());
		}
	}

	private void attachFrom(Part part, String issueId) throws Exception {
		Object content;
		try {
			content = part.getContent();
		}
		catch (Exception ex) {
			// Unparseable part (e.g. unknown encoding) — skip rather than fail.
			return;
		}
		if (content instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				attachFrom(multipart.getBodyPart(i), issueId);
			}
			return;
		}
		if (!isAttachment(part)) {
			return;
		}
		byte[] data;
		try (InputStream in = part.getInputStream()) {
			data = in.readAllBytes();
		}
		if (data.length == 0) {
			return;
		}
		if (data.length > storage.maxUploadBytes()) {
			log.warn("Skipping oversized e-mail attachment ({} bytes) for {}", data.length, issueId);
			return;
		}
		String fileName = attachmentName(part);
		String contentType = baseContentType(part);
		String objectKey = UUID.randomUUID().toString();
		storage.putObject(objectKey, data, contentType);
		attachments.add(issueId, Issue.Attachment.builder()
				.id(UUID.randomUUID().toString())
				.fileName(fileName)
				.contentType(contentType)
				.size(data.length)
				.objectKey(objectKey)
				.uploadedAt(Instant.now())
				.build());
		log.info("Attached {} ({} bytes) from e-mail to {}", fileName, data.length, issueId);
	}

	/**
	 * A part is a real attachment when it is flagged {@code attachment}/{@code inline}
	 * or carries a file name. Text parts without a file name are the message body
	 * and must not be attached.
	 */
	private boolean isAttachment(Part part) throws Exception {
		String disposition = part.getDisposition();
		boolean named = part.getFileName() != null && !part.getFileName().isBlank();
		if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || Part.INLINE.equalsIgnoreCase(disposition)) {
			return named || !part.isMimeType("text/*");
		}
		// No disposition: only treat it as an attachment if it has a file name and
		// isn't the plain/HTML body carried by multipart/alternative.
		return named && !part.isMimeType("text/plain") && !part.isMimeType("text/html");
	}

	private String attachmentName(Part part) throws Exception {
		String raw = part.getFileName();
		if (raw == null || raw.isBlank()) {
			return "attachment";
		}
		try {
			// Decode RFC 2047 / RFC 2231 encoded names (umlauts etc.).
			return MimeUtility.decodeText(raw);
		}
		catch (Exception ex) {
			return raw;
		}
	}

	private String baseContentType(Part part) throws Exception {
		String raw = part.getContentType();
		if (raw == null || raw.isBlank()) {
			return "application/octet-stream";
		}
		try {
			// Strip parameters (e.g. "; name=..."), keep just "type/subtype".
			return new ContentType(raw).getBaseType();
		}
		catch (Exception ex) {
			return "application/octet-stream";
		}
	}

	/**
	 * Extracts a message body suitable for a Markdown description. Prefers the
	 * {@code text/plain} alternative; when a mail carries only {@code text/html}
	 * (single-part or multipart) the HTML is converted to clean Markdown rather than
	 * dumped raw or naively de-tagged. Walks the full MIME tree so a body nested in
	 * {@code multipart/mixed > multipart/alternative} is still found.
	 */
	private String textOf(Message message) throws Exception {
		BodyParts body = new BodyParts();
		collectBody(message, body);
		if (body.plain != null && !body.plain.isBlank()) {
			return body.plain;
		}
		if (body.html != null && !body.html.isBlank()) {
			return HtmlToMarkdown.convert(body.html);
		}
		return "";
	}

	/**
	 * Depth-first walk capturing the first text/plain and first text/html body parts.
	 * Attachment-disposition parts are skipped so a {@code .txt}/{@code .html} file
	 * attachment is never mistaken for the message body.
	 */
	private void collectBody(Part part, BodyParts out) throws Exception {
		if (isAttachment(part)) {
			return;
		}
		Object content;
		try {
			content = part.getContent();
		}
		catch (Exception ex) {
			// Unparseable part (e.g. unknown encoding) — skip rather than fail.
			return;
		}
		if (content instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				collectBody(multipart.getBodyPart(i), out);
			}
			return;
		}
		if (out.plain == null && part.isMimeType("text/plain")) {
			out.plain = String.valueOf(content);
		}
		else if (out.html == null && part.isMimeType("text/html")) {
			out.html = String.valueOf(content);
		}
	}

	/** Mutable accumulator for the body parts found while walking the MIME tree. */
	private static final class BodyParts {
		private String plain;
		private String html;
	}

	private String truncate(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}
}
