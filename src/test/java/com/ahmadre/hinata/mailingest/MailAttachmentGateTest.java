package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.TestImages;
import com.ahmadre.hinata.storage.TestStorage;
import com.ahmadre.hinata.storage.TestUploadGates;
import com.ahmadre.hinata.storage.UploadKind;
import com.ahmadre.hinata.storage.UploadRefusedEvent;
import com.ahmadre.hinata.user.UserService;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An attachment of a mail is a stranger's bytes under a stranger's label (HIN-177). The gate takes
 * its type from the content; what it refuses is not stored, and the ticket still arrives, with a
 * line that says which attachment was left out and why.
 */
class MailAttachmentGateTest {

	private final Map<String, StorageService.StoredObject> objects = new ConcurrentHashMap<>();
	private final List<UploadRefusedEvent> refused = new ArrayList<>();
	private IssueService issues;
	private AttachmentStore attachments;
	private ProjectService projects;
	private UserService users;
	private SettingsService settings;
	private ResourceBundleMessageSource messages;
	private EmailIngestService service;
	/** The ticket the service created last. */
	private Issue ticket;

	@BeforeEach
	void setUp() {
		issues = mock(IssueService.class);
		attachments = mock(AttachmentStore.class);
		when(issues.findByInboundMessageId(anyString())).thenReturn(Optional.empty());
		when(issues.create(any(), isNull())).thenAnswer(call -> {
			ticket = call.getArgument(0);
			ticket.setId("i1");
			return ticket;
		});
		// What the service notes after the ticket exists lands on the ticket, as the real one does.
		doAnswer(call -> {
			ticket.setRefusedAttachments(call.getArgument(1));
			RichText description = call.getArgument(2);
			ticket.setDescription(description.text());
			return null;
		}).when(issues).noteRefusedAttachments(anyString(), any(), any());
		projects = mock(ProjectService.class);
		when(projects.get("p1")).thenReturn(Project.builder().id("p1").memberIds(new ArrayList<>()).build());
		users = mock(UserService.class);
		when(users.findActiveByEmail(anyString())).thenReturn(Optional.empty());
		settings = mock(SettingsService.class);
		when(settings.instanceLocale()).thenReturn(Locale.GERMAN);
		messages = new ResourceBundleMessageSource();
		messages.setBasenames("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);
		service = service(com.ahmadre.hinata.richtext.TestTextChecks.allowAll());
	}

	private EmailIngestService service(com.ahmadre.hinata.richtext.TextCheck textCheck) {
		return new EmailIngestService(mock(IngestConnectionRepository.class), issues, new RichTextService(),
				projects, mock(NotificationService.class),
				TestStorage.inMemory(TestUploadGates.counting(refused), objects), attachments, users,
				TestUploadGates.counting(refused), settings, messages, textCheck, mock(IngestConnectionService.class));
	}

	private static IngestConnection connection() {
		return IngestConnection.builder().id("conn-1").projectId("p1").build();
	}

	/** A mail with a body and two attachments: a real PDF, and a program that calls itself one. */
	private static MimeMessage mail() throws Exception {
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new InternetAddress("stranger@example.org"));
		message.setSubject("Rechnung");
		message.setHeader("Message-ID", "<m1@example.org>");
		MimeBodyPart body = new MimeBodyPart();
		body.setText("Anbei die Rechnung.", "utf-8");
		MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(body);
		parts.addBodyPart(attachment("angebot.pdf", "application/pdf", TestImages.pdf()));
		parts.addBodyPart(attachment("rechnung.pdf", "application/pdf", TestImages.exe()));
		message.setContent(parts);
		message.saveChanges();
		// Read back as the IMAP folder hands it over, not as the object just built.
		java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
		message.writeTo(raw);
		return new MimeMessage(Session.getInstance(new Properties()),
				new java.io.ByteArrayInputStream(raw.toByteArray()));
	}

	private static MimeBodyPart attachment(String name, String type, byte[] data) throws Exception {
		MimeBodyPart part = new MimeBodyPart();
		part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, type)));
		part.setFileName(name);
		part.setDisposition(MimeBodyPart.ATTACHMENT);
		return part;
	}

	private Issue created() {
		ArgumentCaptor<Issue> created = ArgumentCaptor.forClass(Issue.class);
		verify(issues).create(created.capture(), isNull());
		return created.getValue();
	}

	@Test
	void aProgramDeclaredAsPdfIsRefusedAndTheTicketSaysSo() throws Exception {
		service.createIssueFrom(mail(), connection());

		Issue issue = created();
		assertThat(issue.getDescription())
				.contains("Anbei die Rechnung.")
				.contains("Anhang rechnung.pdf abgelehnt: Dieser Dateityp ist hier nicht erlaubt");
		assertThat(issue.getDescription()).doesNotContain("angebot.pdf");
		assertThat(issue.getRefusedAttachments())
				.containsExactly(new Issue.RefusedAttachment("rechnung.pdf", "type"));
		assertThat(refused).containsExactly(new UploadRefusedEvent(UploadKind.MAIL_ATTACHMENT, "type"));

		ArgumentCaptor<Issue.Attachment> stored = ArgumentCaptor.forClass(Issue.Attachment.class);
		verify(attachments).add(eq("i1"), stored.capture());
		assertThat(stored.getValue().getFileName()).isEqualTo("angebot.pdf");
		assertThat(stored.getValue().getContentType()).isEqualTo("application/pdf");
		assertThat(objects).hasSize(1);
		assertThat(objects.values().iterator().next().data()).isEqualTo(TestImages.pdf());
	}

	/** Spam the text check refuses costs no download: not one of its attachments is read. */
	@Test
	void aMailTheTextCheckRefusesReadsNoAttachment() throws Exception {
		service(com.ahmadre.hinata.richtext.TestTextChecks.refusing("rechnung")).createIssueFrom(mail(), connection());

		verify(issues, never()).create(any(), any());
		assertThat(refused).isEmpty();
		assertThat(objects).isEmpty();
	}

	/**
	 * Rebuilding the description writes the same line again from what was noted at ingest, so the
	 * ticket reads as current; no attachment is checked again, and nothing is counted.
	 */
	@Test
	void rebuildingTheDescriptionKeepsTheLineAndCountsNothing() throws Exception {
		service.createIssueFrom(mail(), connection());
		Issue issue = created();
		when(issues.findByInboundMessageId("<m1@example.org>")).thenReturn(Optional.of(issue));

		EmailIngestService.Outcome outcome = service.reprocessOne(mail(), connection(), false);

		assertThat(outcome).isEqualTo(EmailIngestService.Outcome.SKIPPED);
		verify(issues, never()).replaceIngestedDescription(anyString(), any());
		assertThat(refused).hasSize(1);
	}

	@Test
	void aSendersFileNameLosesItsPathAndControlCharacters() {
		assertThat(EmailIngestService.safeFileName("..\\..\\boot.ini")).isEqualTo(".._.._boot.ini");
		assertThat(EmailIngestService.safeFileName("a/b\u0007c.pdf")).isEqualTo("a_b_c.pdf");
		assertThat(EmailIngestService.safeFileName("  ")).isEqualTo("attachment");
		assertThat(EmailIngestService.safeFileName("x".repeat(300))).hasSize(200);
	}

	@Test
	void aFileNameNeverReadsAsMarkdown() {
		assertThat(EmailIngestService.codeSpan("[klick](https://evil.example)")).isEqualTo(
				"`[klick](https://evil.example)`");
		assertThat(EmailIngestService.codeSpan("a``b")).isEqualTo("```a``b```");
		assertThat(EmailIngestService.codeSpan("`a")).isEqualTo("`` `a ``");
	}
}
