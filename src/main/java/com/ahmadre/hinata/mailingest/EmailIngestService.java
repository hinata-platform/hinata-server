package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E-mail-to-ticket: polls the IMAP mailbox configured in the admin area and
 * turns unseen messages into issues in the configured default project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailIngestService {

	private final SettingsService settings;
	private final IssueService issues;

	private final AtomicLong lastRun = new AtomicLong(0);

	@Scheduled(fixedDelay = 15000)
	public void poll() {
		ServerSettings.EmailIngest config = settings.get().getEmailIngest();
		if (!config.isEnabled() || config.getHost() == null || config.getDefaultProjectId() == null) {
			return;
		}
		long now = Instant.now().getEpochSecond();
		if (now - lastRun.get() < config.getPollSeconds()) {
			return;
		}
		lastRun.set(now);
		try {
			ingest(config);
		}
		catch (Exception ex) {
			log.warn("E-mail ingestion failed: {}", ex.getMessage());
		}
	}

	private void ingest(ServerSettings.EmailIngest config) throws Exception {
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
					createIssueFrom(message, config.getDefaultProjectId());
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
