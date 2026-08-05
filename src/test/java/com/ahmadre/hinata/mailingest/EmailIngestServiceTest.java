package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
		// A gate that lets everything through: this test is about attribution, and a
		// verdict is never null in the product, so the stub returns a real one.
		ModerationService moderation = mock(ModerationService.class);
		lenient().when(moderation.assessText(anyString(), any()))
				.thenReturn(ModerationVerdict.disabled());
		service = new EmailIngestService(mock(IngestConnectionRepository.class), issues,
				new RichTextService(), projects, notifications, storage,
				mock(AttachmentStore.class), users, moderation, mock(ModerationRecorder.class));
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
}
