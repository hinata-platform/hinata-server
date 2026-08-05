package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationException;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
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
import java.util.ArrayList;
import java.util.List;
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
	private final RichTextService richText;
	private final ProjectService projects;
	private final NotificationService notifications;
	private final StorageService storage;
	private final AttachmentStore attachments;
	private final UserService users;
	private final ModerationService moderation;
	private final ModerationRecorder moderationRecorder;

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
					try {
						createIssueFrom(message, config);
					}
					catch (ModerationException refused) {
						// A refusal is a verdict, not a fault: the message is done with,
						// so it is flagged SEEN below like any other. Caught per message
						// rather than per batch — one refused mail must not stop the
						// mailbox from being drained, or a single crafted message would
						// stall every ticket behind it.
						log.warn("Refused an inbound message on {}@{}: {}",
								config.getUsername(), config.getHost(), refused.getMessage());
					}
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

	/**
	 * Turns one inbound message into a ticket. Package-private so the mail→issue
	 * mapping (author resolution, dedupe, fan-out) can be tested without an IMAP server.
	 */
	void createIssueFrom(Message message, IngestConnection config) throws Exception {
		String projectId = config.getProjectId();
		String subject = message.getSubject() != null ? message.getSubject() : "(no subject)";
		String from = senderOf(message);
		// Message-ID dedupe is the PRIMARY guard, the SEEN flag only secondary: if
		// the flag write failed or the process died between create and flag (or two
		// instances poll the same mailbox), the message stays UNSEEN and would
		// otherwise become a duplicate ticket on the next tick.
		String messageId = messageIdOf(message);
		if (messageId != null && issues.findByInboundMessageId(messageId).isPresent()) {
			log.info("Skipping already-ingested message {}", messageId);
			return; // caller still sets SEEN
		}
		String body = buildDescription(from, message);
		// The one ingress in the product where the author proved nothing: anyone who
		// learns the ingest address can put content in front of the whole team, and
		// no colleague's name is attached to it. So it is judged before a ticket
		// exists at all, subject and body together — a subject alone is short enough
		// to look harmless while the body carries the payload, and vice versa.
		//
		// Assessed rather than checked: a refusal here must drop this one message
		// and leave the mailbox being drained, not raise a 422 at a caller that
		// isn't there. Nothing is persisted, so the record carries the verdict and
		// no id, which is exactly what ModerationRecorder documents for that case.
		ModerationVerdict verdict =
				moderation.assessText(subject + "\n\n" + body, ModerationSurface.EMAIL_INGEST);
		if (verdict.isBlocking()) {
			log.warn("Dropped an inbound e-mail from {} into project {}: refused as {}",
					from, projectId, verdict.primaryCategory());
			moderationRecorder.record(verdict, ModerationSurface.EMAIL_INGEST,
					new ModerationRecorder.Target("email", null, projectId, null, from));
			return; // caller still sets SEEN — a refused mail must not be re-ingested
		}
		// An e-mail body arrives as markdown (HtmlToMarkdown); storage is Lexical.
		RichText ingested = richText.fromMarkdown(body, ModerationSurface.EMAIL_INGEST);
		String reporterId = resolveReporterId(from);
		Issue issue = Issue.builder()
				.projectId(projectId)
				.title(truncate(subject, 300))
				.description(ingested.text())
				.descriptionDoc(ingested.doc())
				.type(Issue.Type.TASK)
				.reporterId(reporterId)
				.reporterEmail(from)
				.inboundMessageId(messageId)
				.inboundSubject(truncate(subject, 300))
				.ingestConnectionId(config.getId())
				.build();
		// Created with a null actor on purpose: the sender is the ticket's author, not
		// an authenticated creator. Passing them as the actor would demand project
		// membership (A01) and abort ingestion for every non-member sender; IssueService
		// leaves a builder-set reporterId untouched when the actor is null.
		Issue created = issues.create(issue, null);
		// The flag that survived the block band now has a ticket to point at.
		moderationRecorder.record(verdict, ModerationSurface.EMAIL_INGEST,
				new ModerationRecorder.Target("issue", created.getId(), projectId, reporterId,
						created.getReadableId()));
		log.info("Created {} from e-mail by {}{}", created.getReadableId(), from,
				reporterId != null ? " (author resolved to user " + reporterId + ")" : "");
		notifyMembers(created, projectId, from, reporterId);
		attachFiles(message, created.getId());
	}

	/**
	 * The platform account behind an inbound sender address, so an e-mail author shows
	 * up as the ticket's author and rides along on the issue's watcher fan-out (state
	 * changes, comments, assignment) for their own request. {@code null} when the
	 * address belongs to no usable account — the ticket then stays author-less, as
	 * every ingested ticket did before.
	 *
	 * <p>Attribution only: a {@code From} header is unauthenticated and spoofable, and
	 * being an issue's reporter grants no access anywhere (project membership alone
	 * decides that, so a resolved non-member still cannot open the ticket).
	 */
	private String resolveReporterId(String from) {
		if (from == null || from.isBlank() || UNKNOWN_SENDER.equalsIgnoreCase(from)) {
			return null;
		}
		return users.findActiveByEmail(from).map(User::getId).orElse(null);
	}

	/** The description body written for an ingested message: an attribution header
	 * plus the parsed (plain or HTML→Markdown) mail body. */
	private String buildDescription(String from, Message message) throws Exception {
		return DESCRIPTION_HEADER + "**" + from + "**\n\n---\n\n" + truncate(textOf(message), 20000);
	}

	/** Stand-in for a message with no usable {@code From} header. */
	private static final String UNKNOWN_SENDER = "unknown";

	private String senderOf(Message message) throws Exception {
		return message.getFrom() != null && message.getFrom().length > 0
				? ((InternetAddress) message.getFrom()[0]).getAddress()
				: UNKNOWN_SENDER;
	}

	private String messageIdOf(Message message) throws Exception {
		String[] ids = message.getHeader("Message-ID");
		return ids != null && ids.length > 0 ? ids[0] : null;
	}

	/**
	 * Re-reads a connection's mailbox (read-only — seen flags are left untouched) and
	 * reconciles it against existing tickets, using the current body parser.
	 *
	 * <p>Every scanned message whose ticket still exists has its description rebuilt
	 * (only auto-generated descriptions are rewritten, so manual edits survive).
	 * Messages with <em>no</em> matching ticket are only turned into new tickets when
	 * {@code createMissing} is true — the caller's explicit opt-in — because a missing
	 * ticket may well have been deleted on purpose and must not silently reappear.
	 * Messages without a {@code Message-ID} are never re-created (they cannot be
	 * de-duplicated, so a re-run would keep duplicating them). A single unreadable
	 * message is logged and skipped, never aborting the run.
	 *
	 * @param createMissing whether to (re-)create tickets for e-mails that have none
	 * @return how many messages were scanned, rebuilt, and newly created
	 */
	public ReprocessResult reprocess(IngestConnection config, boolean createMissing) {
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
		int created = 0;
		try (Store store = session.getStore(protocol)) {
			store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
			Folder folder = store.getFolder(config.getFolder());
			folder.open(Folder.READ_ONLY);
			try {
				for (Message message : folder.getMessages()) {
					scanned++;
					switch (reprocessOne(message, config, createMissing)) {
						case UPDATED -> updated++;
						case CREATED -> created++;
						case SKIPPED -> { /* left as-is */ }
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
		log.info("Reprocessed mailbox {}@{} (createMissing={}): {} scanned, {} rebuilt, {} created",
				config.getUsername(), config.getHost(), createMissing, scanned, updated, created);
		return new ReprocessResult(scanned, updated, created);
	}

	private enum Outcome { UPDATED, CREATED, SKIPPED }

	/**
	 * Reconciles a single message against the tickets. Rebuilds the matching ticket's
	 * description when it still exists (and is still auto-generated), or creates a new
	 * ticket when none exists and {@code createMissing} is set. Best-effort: a broken
	 * message is skipped, not fatal.
	 */
	private Outcome reprocessOne(Message message, IngestConnection config, boolean createMissing) {
		try {
			String messageId = messageIdOf(message);
			Issue issue = messageId != null
					? issues.findByInboundMessageId(messageId).orElse(null)
					: null;
			if (issue != null) {
				if (!config.getProjectId().equals(issue.getProjectId())
						|| issue.getDescription() == null
						|| !issue.getDescription().startsWith(DESCRIPTION_HEADER)) {
					return Outcome.SKIPPED; // foreign project or a manually edited body
				}
				// Judged on the way in like any other ingest; a refusal lands in the
				// catch below and leaves the existing description untouched.
				RichText rebuilt = richText.fromMarkdown(buildDescription(senderOf(message), message),
						ModerationSurface.EMAIL_INGEST);
				// Compare the derived plain text, not the document: re-converting the
				// same body must read as "already current" even if the converter's
				// output shifts between releases.
				if (rebuilt.text().equals(issue.getDescription())) {
					return Outcome.SKIPPED; // already current
				}
				issues.replaceIngestedDescription(issue.getId(), rebuilt);
				return Outcome.UPDATED;
			}
			// No ticket for this message. Only (re-)create on explicit opt-in, and
			// never for messages we cannot de-duplicate by Message-ID.
			if (createMissing && messageId != null) {
				createIssueFrom(message, config);
				return Outcome.CREATED;
			}
			return Outcome.SKIPPED;
		}
		catch (Exception ex) {
			log.warn("Reprocessing a message in folder {} failed: {}",
					config.getFolder(), ex.getMessage());
			return Outcome.SKIPPED;
		}
	}

	/** Outcome of a {@link #reprocess} run. */
	public record ReprocessResult(int scanned, int updated, int created) {
	}

	/**
	 * Tells the project's members that an issue arrived by e-mail. The sender is left
	 * out when they resolved to a member themselves — they wrote the mail, so a "new
	 * issue via e-mail from you" notice is noise (they still get every later change via
	 * the watcher fan-out). Best-effort: a lookup or delivery failure is logged and
	 * never aborts ticket creation.
	 */
	private void notifyMembers(Issue created, String projectId, String from, String reporterId) {
		try {
			Project project = projects.get(projectId);
			List<String> recipients = new ArrayList<>(
					project.getMemberIds() != null ? project.getMemberIds() : List.<String>of());
			recipients.remove(reporterId);
			notifications.notifyIssueIngested(created, recipients, from);
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
		// This used to call putObject, which trusts its caller absolutely — so an
		// e-mail attachment reached the bucket without the content-type allowlist,
		// without the magic-byte check, and without any classifier, on a content
		// type read verbatim out of a MIME header the sender wrote. It is the same
		// bucket the app serves attachments from, and the sender is a stranger.
		// putChecked applies exactly what the HTTP upload path applies.
		String objectKey;
		try {
			objectKey = storage.putChecked("", data, contentType, fileName,
					ModerationSurface.EMAIL_ATTACHMENT);
		}
		catch (RuntimeException rejected) {
			// One bad attachment costs its own file and nothing else: the ticket and
			// the sender's other files are already worth keeping.
			log.warn("Skipped e-mail attachment {} ({}) for {}: {}", fileName, contentType,
					issueId, rejected.getMessage());
			return;
		}
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
