package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.richtext.TextCheck;
import com.ahmadre.hinata.richtext.TextField;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.UploadCandidate;
import com.ahmadre.hinata.storage.UploadGate;
import com.ahmadre.hinata.storage.UploadKind;
import com.ahmadre.hinata.storage.UploadRefusal;
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
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
	private final UploadGate gate;
	private final SettingsService settings;
	private final MessageSource messages;

	/** A mail is checked like a form (HIN-176); a refused one becomes nothing and is counted. */
	private final TextCheck textCheck;
	private final IngestConnectionService ingestConnections;

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
		String body = bodyOf(message);
		// An e-mail body arrives as markdown (HtmlToMarkdown); storage is Lexical.
		RichText ingested = richText.fromMarkdown(buildDescription(from, body, List.of()));
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
		// Refused by the text check: nothing becomes an issue, no attachment is even read, and
		// nobody is mailed back, or the mailbox would answer spam. The connection counts it,
		// without any of the words.
		TextCheck.Verdicts texts;
		try {
			texts = textCheck.require(new TextCheck.Entry(TextField.ISSUE_TITLE, issue.getTitle(), null),
					new TextCheck.Entry(TextField.ISSUE_DESCRIPTION, issue.getDescription(), null));
		}
		catch (ApiException refused) {
			if (!TextCheck.isRefusal(refused)) {
				throw refused;
			}
			log.info("Message {} for project {} refused by the text check", messageId, projectId);
			ingestConnections.noteTextRefusal(config.getId());
			return; // caller still sets SEEN
		}
		// Created with a null actor on purpose: the sender is the ticket's author, not
		// an authenticated creator. Passing them as the actor would demand project
		// membership (A01) and abort ingestion for every non-member sender; IssueService
		// leaves a builder-set reporterId untouched when the actor is null.
		Issue created = issues.create(issue, null);
		ContentRef ref = new ContentRef(ContentKind.ISSUE, created.getId(), null, created.getProjectId());
		textCheck.afterSave(ref, texts);
		log.info("Created {} from e-mail by {}{}", created.getReadableId(), from,
				reporterId != null ? " (author resolved to user " + reporterId + ")" : "");
		List<Issue.RefusedAttachment> refused = attachFiles(message, created.getId());
		if (!refused.isEmpty()) {
			issues.noteRefusedAttachments(created.getId(), refused,
					richText.fromMarkdown(buildDescription(from, body, refused)));
		}
		notifyMembers(created, projectId, from, reporterId);
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

	/** The parsed (plain or HTML→Markdown) body of [message], as much of it as a description keeps. */
	private String bodyOf(Message message) throws Exception {
		return truncate(textOf(message), 20000);
	}

	/** The description body written for an ingested message: an attribution header,
	 * the mail's [body], and a line for every attachment the upload gate refused. */
	private String buildDescription(String from, String body, List<Issue.RefusedAttachment> refused) {
		StringBuilder description = new StringBuilder(DESCRIPTION_HEADER).append("**").append(from)
				.append("**\n\n---\n\n").append(body);
		Locale locale = settings.instanceLocale();
		for (Issue.RefusedAttachment file : refused) {
			UploadRefusal refusal = UploadRefusal.of(file.reason());
			String reason = messages.getMessage(refusal.messageKey(),
					gate.argumentsOf(refusal, UploadKind.MAIL_ATTACHMENT), locale);
			description.append("\n\n").append(messages.getMessage("ingest.attachmentRefused",
					new Object[] { codeSpan(file.fileName()), reason }, locale));
		}
		return description.toString();
	}

	/**
	 * [name] as an inline code span: a sender chose it, and nothing in it may read as
	 * Markdown, a link least of all. The fence is one backtick longer than the longest
	 * run inside, as CommonMark wants.
	 */
	static String codeSpan(String name) {
		int longest = 0;
		int run = 0;
		for (int i = 0; i < name.length(); i++) {
			run = name.charAt(i) == '`' ? run + 1 : 0;
			longest = Math.max(longest, run);
		}
		String fence = "`".repeat(longest + 1);
		String pad = name.startsWith("`") || name.endsWith("`") ? " " : "";
		return fence + pad + name + pad + fence;
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

	enum Outcome { UPDATED, CREATED, SKIPPED }

	/**
	 * Reconciles a single message against the tickets. Rebuilds the matching ticket's
	 * description when it still exists (and is still auto-generated), or creates a new
	 * ticket when none exists and {@code createMissing} is set. Best-effort: a broken
	 * message is skipped, not fatal. Package-private for the same reason as
	 * {@link #createIssueFrom}.
	 */
	Outcome reprocessOne(Message message, IngestConnection config, boolean createMissing) {
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
				// The refused attachments as they were noted at ingest: none of their bytes is read again.
				RichText rebuilt = richText.fromMarkdown(buildDescription(senderOf(message), bodyOf(message),
						issue.getRefusedAttachments() == null ? List.of() : issue.getRefusedAttachments()));
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
	 * Takes the attachments of [message] into issue [issueId] one at a time, each through the
	 * upload gate first, so no more than one is ever held in memory. Returns those the gate
	 * refused; each refusal is counted. The header's type is a sender's word, so the gate takes
	 * the type from the content. Best-effort: a part that cannot be read is left out, and so is an
	 * empty one; one that cannot be stored is logged and skipped. Without object storage there is
	 * nothing to store and so nothing to check.
	 */
	private List<Issue.RefusedAttachment> attachFiles(Message message, String issueId) {
		List<Issue.RefusedAttachment> refused = new ArrayList<>();
		if (!storage.isConfigured()) {
			return refused;
		}
		try {
			forEachFile(message, candidate -> {
				UploadRefusal refusal = refusalCounted(candidate);
				if (refusal != null) {
					log.info("Refused e-mail attachment {} ({} bytes) for {}: {}", candidate.fileName(),
							candidate.size(), issueId, refusal.code());
					refused.add(new Issue.RefusedAttachment(candidate.fileName(), refusal.code()));
					return;
				}
				attach(candidate, issueId);
			});
		}
		catch (Exception ex) {
			log.warn("Extracting e-mail attachments failed: {}", ex.getMessage());
		}
		return refused;
	}

	private UploadRefusal refusalCounted(UploadCandidate candidate) {
		try {
			gate.check(candidate);
			return null;
		}
		catch (ApiException refused) {
			return UploadRefusal.of(refused.getDetails().get("reason"));
		}
	}

	/** Hands every attachment of [part] to [file], one after the other. */
	private void forEachFile(Part part, Consumer<UploadCandidate> file) throws Exception {
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
				forEachFile(multipart.getBodyPart(i), file);
			}
			return;
		}
		if (!isAttachment(part)) {
			return;
		}
		// One byte past the limit is enough to know it is too large; no more is held.
		long limit = gate.maxBytes(UploadKind.MAIL_ATTACHMENT);
		byte[] data;
		try (InputStream in = part.getInputStream()) {
			data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, limit + 1));
		}
		if (data.length == 0) {
			return;
		}
		file.accept(UploadCandidate.of(UploadKind.MAIL_ATTACHMENT, data, baseContentType(part),
				safeFileName(attachmentName(part))));
	}

	/** Stores [candidate], which the gate let through, as an attachment of issue [issueId]. */
	private void attach(UploadCandidate candidate, String issueId) {
		try {
			StorageService.Stored stored = storage.putUserObject(StorageService.newObjectKey(), candidate);
			attachments.add(issueId, Issue.Attachment.builder()
					.id(UUID.randomUUID().toString())
					.fileName(candidate.fileName())
					.contentType(stored.contentType())
					.size(stored.size())
					.objectKey(stored.objectKey())
					.uploadedAt(Instant.now())
					.build());
			log.info("Attached {} ({} bytes) from e-mail to {}", candidate.fileName(), stored.size(), issueId);
		}
		catch (RuntimeException ex) {
			log.warn("Storing e-mail attachment {} for {} failed: {}", candidate.fileName(), issueId,
					ex.getMessage());
		}
	}

	/**
	 * [name] as the gate takes it: no control character and no path separator, which a
	 * sender may well put in, and no longer than the gate allows.
	 */
	static String safeFileName(String name) {
		StringBuilder safe = new StringBuilder(name.length());
		name.codePoints().forEach(c -> safe.appendCodePoint(
				Character.isISOControl(c) || c == '/' || c == '\\' ? '_' : c));
		String cleaned = safe.toString().strip();
		if (cleaned.isEmpty()) {
			return "attachment";
		}
		if (cleaned.length() <= UploadGate.FILE_NAME_MAX) {
			return cleaned;
		}
		// Never half a character: a cut between the two halves of a surrogate pair is no text.
		int end = Character.isHighSurrogate(cleaned.charAt(UploadGate.FILE_NAME_MAX - 1))
				? UploadGate.FILE_NAME_MAX - 1 : UploadGate.FILE_NAME_MAX;
		return cleaned.substring(0, end);
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
