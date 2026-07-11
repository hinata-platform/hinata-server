package com.ahmadre.hinata.mailingest;

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
import jakarta.mail.internet.MimeMultipart;
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
					createIssueFrom(message, config.getProjectId());
					message.setFlag(Flags.Flag.SEEN, true);
				}
			}
			finally {
				folder.close(false);
			}
		}
	}

	private void createIssueFrom(Message message, String projectId) throws Exception {
		String subject = message.getSubject() != null ? message.getSubject() : "(no subject)";
		String from = message.getFrom() != null && message.getFrom().length > 0
				? ((InternetAddress) message.getFrom()[0]).getAddress()
				: "unknown";
		Issue issue = Issue.builder()
				.projectId(projectId)
				.title(truncate(subject, 300))
				.description("Created from e-mail by **" + from + "**\n\n---\n\n"
						+ truncate(textOf(message), 20000))
				.type(Issue.Type.TASK)
				.reporterEmail(from)
				.build();
		Issue created = issues.create(issue, null);
		log.info("Created {} from e-mail by {}", created.getReadableId(), from);
		notifyMembers(created, projectId, from);
		attachFiles(message, created.getId());
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

	private String textOf(Message message) throws Exception {
		Object content = message.getContent();
		if (content instanceof String text) {
			return text;
		}
		if (content instanceof MimeMultipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				var part = multipart.getBodyPart(i);
				if (part.isMimeType("text/plain")) {
					return String.valueOf(part.getContent());
				}
			}
			for (int i = 0; i < multipart.getCount(); i++) {
				var part = multipart.getBodyPart(i);
				if (part.isMimeType("text/html")) {
					return String.valueOf(part.getContent()).replaceAll("<[^>]+>", " ");
				}
			}
		}
		return "";
	}

	private String truncate(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}
}
