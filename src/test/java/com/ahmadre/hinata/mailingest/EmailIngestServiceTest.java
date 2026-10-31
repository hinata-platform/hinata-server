package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.TestUploadGates;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers who an ingested ticket is attributed to. A sender that exists on the platform
 * becomes the ticket's author so they ride along on the issue's watcher fan-out and hear
 * about changes to their own request; anyone else leaves the ticket author-less, exactly
 * as every ingested ticket was before.
 */
class EmailIngestServiceTest {

	private IssueService issues;
	private ProjectService projects;
	private NotificationService notifications;
	private UserService users;
	private EmailIngestService service;

	@BeforeEach
	void setUp() {
		issues = mock(IssueService.class);
		projects = mock(ProjectService.class);
		notifications = mock(NotificationService.class);
		users = mock(UserService.class);
		StorageService storage = mock(StorageService.class);
		lenient().when(storage.isConfigured()).thenReturn(false); // no attachment extraction
		lenient().when(issues.findByInboundMessageId(anyString())).thenReturn(Optional.empty());
		lenient().when(issues.create(any(), isNull())).thenAnswer(call -> call.getArgument(0));
		lenient().when(projects.get("p1")).thenReturn(Project.builder().id("p1")
				.memberIds(new ArrayList<>(List.of("u-author", "u-lead"))).build());
		lenient().when(users.findActiveByEmail(anyString())).thenReturn(Optional.empty());
		SettingsService settings = mock(SettingsService.class);
		lenient().when(settings.instanceLocale()).thenReturn(Locale.GERMAN);
		service = new EmailIngestService(mock(IngestConnectionRepository.class), issues,
				new RichTextService(), projects, notifications, storage,
				mock(AttachmentStore.class), users, TestUploadGates.open(), settings,
				new StaticMessageSource(), com.ahmadre.hinata.richtext.TestTextChecks.allowAll(),
				mock(IngestConnectionService.class));
	}

	/** Refused by the text check (HIN-176): no issue, no reply, one more on the connection's count. */
	@Test
	void aMailTheTextCheckRefusesBecomesNothingAndIsCounted() throws Exception {
		IngestConnectionService connections = mock(IngestConnectionService.class);
		EmailIngestService refusing = new EmailIngestService(mock(IngestConnectionRepository.class), issues,
				new RichTextService(), projects, notifications, mock(StorageService.class), mock(AttachmentStore.class),
				users, TestUploadGates.open(), mock(SettingsService.class), new StaticMessageSource(),
				com.ahmadre.hinata.richtext.TestTextChecks.refusing("fire"), connections);

		refusing.createIssueFrom(message("kim@example.org"), connection());

		verify(issues, never()).create(any(), any());
		verify(connections).noteTextRefusal("conn-1");
		verifyNoInteractions(notifications);
	}

	private IngestConnection connection() {
		return IngestConnection.builder().id("conn-1").projectId("p1").build();
	}

	/** A minimal RFC 822 message, parsed the way the IMAP folder would hand it over. */
	private MimeMessage message(String from) throws Exception {
		String raw = "From: " + from + "\r\n"
				+ "Subject: Printer is on fire\r\n"
				+ "Message-ID: <m1@example.org>\r\n"
				+ "Content-Type: text/plain; charset=utf-8\r\n\r\n"
				+ "It really is.\r\n";
		return new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
	}

	private Issue ingest(String from) throws Exception {
		service.createIssueFrom(message(from), connection());
		ArgumentCaptor<Issue> created = ArgumentCaptor.forClass(Issue.class);
		verify(issues).create(created.capture(), isNull());
		return created.getValue();
	}

	@Test
	void attributesTheTicketToTheSendersAccount() throws Exception {
		when(users.findActiveByEmail("someone@example.org"))
				.thenReturn(Optional.of(User.builder().id("u-author").build()));

		Issue created = ingest("Some One <someone@example.org>");

		assertThat(created.getReporterId()).isEqualTo("u-author");
		assertThat(created.getReporterEmail()).isEqualTo("someone@example.org");
	}

	@Test
	void leavesTheTicketAuthorLessWhenTheSenderIsNotOnThePlatform() throws Exception {
		Issue created = ingest("stranger@example.org");

		assertThat(created.getReporterId()).isNull();
		assertThat(created.getReporterEmail()).isEqualTo("stranger@example.org");
	}

	@Test
	void neverLooksUpAMessageWithoutAUsableSender() throws Exception {
		String raw = "Subject: No sender\r\nMessage-ID: <m2@example.org>\r\n\r\nbody\r\n";
		MimeMessage headerless = new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

		service.createIssueFrom(headerless, connection());

		ArgumentCaptor<Issue> created = ArgumentCaptor.forClass(Issue.class);
		verify(issues).create(created.capture(), isNull());
		assertThat(created.getValue().getReporterId()).isNull();
		verify(users, never()).findActiveByEmail(anyString());
	}

	@Test
	void doesNotTellTheSenderAboutTheirOwnMail() throws Exception {
		when(users.findActiveByEmail("someone@example.org"))
				.thenReturn(Optional.of(User.builder().id("u-author").build()));

		ingest("someone@example.org");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<String>> recipients =
				ArgumentCaptor.forClass(Collection.class);
		verify(notifications).notifyIssueIngested(any(), recipients.capture(),
				eq("someone@example.org"));
		assertThat(recipients.getValue())
				.as("the author wrote the mail; the ISSUE_INGESTED notice is for everyone else")
				.containsExactly("u-lead");
	}

	@Test
	void stillNotifiesEveryMemberWhenTheSenderIsUnknown() throws Exception {
		ingest("stranger@example.org");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<String>> recipients =
				ArgumentCaptor.forClass(Collection.class);
		verify(notifications).notifyIssueIngested(any(), recipients.capture(), anyString());
		assertThat(recipients.getValue()).containsExactlyInAnyOrder("u-author", "u-lead");
	}

	// --- HIN-181: an HTML mail keeps its layout ---------------------------------

	/** A mail with a plain and an HTML part, as most senders send one. */
	private MimeMessage alternative(String plain, String html) throws Exception {
		return alternative(plain, html, "");
	}

	private MimeMessage alternative(String plain, String html, String extraHeaders) throws Exception {
		String raw = extraHeaders + "From: shop@kundenservice.example.com\r\n"
				+ "Subject: Deine eSIM\r\n"
				+ "Message-ID: <m2@example.org>\r\n"
				+ "MIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/alternative; boundary=b1\r\n\r\n"
				+ "--b1\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + plain + "\r\n"
				+ "--b1\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" + html + "\r\n"
				+ "--b1--\r\n";
		return new MimeMessage(Session.getInstance(new Properties()),
				new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
	}

	private Issue ingested(MimeMessage message) throws Exception {
		service.createIssueFrom(message, connection());
		ArgumentCaptor<Issue> created = ArgumentCaptor.forClass(Issue.class);
		verify(issues).create(created.capture(), isNull());
		return created.getValue();
	}

	@Test
	void anHtmlMailKeepsItsLayoutUnderTheHeader() throws Exception {
		String html = "<table width=\"600\" align=\"center\" bgcolor=\"#ffffff\"><tr>"
				+ "<td width=\"280\"><img src=\"https://mail.example.com/step.png\" width=\"280\" height=\"180\"></td>"
				+ "<td>Schritt eins</td></tr></table>";

		Issue issue = ingested(alternative("Schritt eins (Textfassung)", html));

		assertThat(issue.getDescription()).startsWith("Created from e-mail by shop@kundenservice.example.com\n")
				.contains("Schritt eins").doesNotContain("Textfassung");
		var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(issue.getDescriptionDoc());
		var blocks = document.path("root").path("children");
		assertThat(blocks.get(0).path("children").get(1).path("format").asInt()).as("sender in bold").isEqualTo(1);
		assertThat(blocks.get(1).path("type").asText()).isEqualTo("horizontalrule");
		assertThat(issue.getDescriptionDoc()).contains("\"templateColumns\":\"280px 1fr\"")
				.contains("https://mail.example.com/step.png");
	}

	@Test
	void anHtmlMailPastTheBoundsGoesTheMarkdownWay() throws Exception {
		String html = "<p>" + "Zeile<br>".repeat(25_000) + "</p>";

		Issue issue = ingested(alternative("Die Textfassung", html));

		assertThat(issue.getDescription()).contains("Die Textfassung");
		assertThat(issue.getDescriptionDoc()).doesNotContain("layout-container");
	}

	@Test
	void reprocessGivesAnOldDescriptionItsLayout() throws Exception {
		String html = "<table><tr><td>Links</td><td>Rechts</td></tr></table>";
		RichTextService markdownOnly = new RichTextService();
		var old = markdownOnly.fromMarkdown("Created from e-mail by **shop@kundenservice.example.com**\n\n---\n\nLinks\n\nRechts");
		Issue stored = Issue.builder().id("i1").projectId("p1").description(old.text()).descriptionDoc(old.doc()).build();
		when(issues.findByInboundMessageId("<m2@example.org>")).thenReturn(Optional.of(stored));

		EmailIngestService.Outcome outcome = service.reprocessOne(alternative("Links Rechts", html), connection(), false);

		assertThat(outcome).isEqualTo(EmailIngestService.Outcome.UPDATED);
		ArgumentCaptor<com.ahmadre.hinata.richtext.RichText> rebuilt =
				ArgumentCaptor.forClass(com.ahmadre.hinata.richtext.RichText.class);
		verify(issues).replaceIngestedDescription(eq("i1"), rebuilt.capture());
		assertThat(rebuilt.getValue().doc()).contains("layout-container");
	}

	@Test
	void reprocessLeavesADescriptionAloneThatIsAlreadyCurrent() throws Exception {
		String html = "<table><tr><td>Links</td><td>Rechts</td></tr></table>";
		MimeMessage message = alternative("Links Rechts", html);
		var current = service.description("shop@kundenservice.example.com",
				new EmailIngestService.MailBody("Links Rechts", html), List.of());
		Issue stored = Issue.builder().id("i1").projectId("p1").description(current.text())
				.descriptionDoc(current.doc()).build();
		when(issues.findByInboundMessageId("<m2@example.org>")).thenReturn(Optional.of(stored));

		assertThat(service.reprocessOne(message, connection(), false)).isEqualTo(EmailIngestService.Outcome.SKIPPED);
		verify(issues, never()).replaceIngestedDescription(anyString(), any());
	}

	@Test
	void anIngestedIssueRecordsWhetherTheReceivingServerVouchedForItsSender() throws Exception {
		Issue verified = ingested(alternative("x", "<p>x</p>",
				"Authentication-Results: mx.example.org; dkim=pass header.d=example.com; dmarc=pass\r\n"));
		assertThat(verified.getSenderVerified()).isTrue();
	}

	@Test
	void anIngestedIssueWithoutAVouchIsUnverified() throws Exception {
		assertThat(ingested(alternative("x", "<p>x</p>")).getSenderVerified()).isFalse();
	}
}
