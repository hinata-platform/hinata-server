package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the plain-text {@code preview()} teaser and reply-notification fan-out. */
class NotificationServiceTest {

	private UserRepository users;
	private NotificationRepository notifications;
	private NotificationService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		service = new NotificationService(notifications, users,
				mock(MailService.class), mock(PushService.class), mock(GatewayService.class));
	}

	@Test
	void replyNotifiesParentAuthorWithAnchoredCommentLink() {
		User replier = User.builder().id("u2").displayName("Sam").active(true).build();
		User parentAuthor = User.builder().id("u1").displayName("Rebar").active(true).build();
		when(users.findById("u1")).thenReturn(Optional.of(parentAuthor));
		Issue issue = Issue.builder().id("i1").readableId("MOB-9").title("Login bug")
				.watcherIds(new ArrayList<>()).build();
		IssueComment reply = IssueComment.builder().id("c99").issueId("i1").authorId("u2")
				.text("good catch").replyToId("root").replyToAuthorId("u1").build();

		service.notifyComment(issue, replier, "good catch", reply);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getValue()).satisfies(n -> {
			assertThat(n.getType()).isEqualTo(Notification.Type.COMMENT_REPLY);
			assertThat(n.getUserId()).isEqualTo("u1");
			assertThat(n.getLink()).isEqualTo("/issues/MOB-9?comment=c99");
		});
	}

	@Test
	void mentionSupersedesReplyForTheSameRecipient() {
		User replier = User.builder().id("u2").displayName("Sam").active(true).build();
		User parentAuthor = User.builder().id("u1").displayName("Rebar").active(true).build();
		when(users.findById("u1")).thenReturn(Optional.of(parentAuthor));
		Issue issue = Issue.builder().id("i1").readableId("MOB-9").title("Login bug")
				.watcherIds(new ArrayList<>()).build();
		// The reply both @mentions and answers u1 — they must be pinged exactly once.
		IssueComment reply = IssueComment.builder().id("c99").issueId("i1").authorId("u2")
				.text("{{user:u1}} thanks").replyToId("root").replyToAuthorId("u1").build();

		service.notifyComment(issue, replier, "{{user:u1}} thanks", reply);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getAllValues()).singleElement().satisfies(
				n -> assertThat(n.getType()).isEqualTo(Notification.Type.MENTION));
	}

	@Test
	void selfReplyDoesNotNotify() {
		User author = User.builder().id("u1").displayName("Rebar").active(true).build();
		Issue issue = Issue.builder().id("i1").readableId("MOB-9").title("Login bug")
				.watcherIds(new ArrayList<>()).build();
		IssueComment reply = IssueComment.builder().id("c99").issueId("i1").authorId("u1")
				.text("adding more").replyToId("root").replyToAuthorId("u1").build();

		service.notifyComment(issue, author, "adding more", reply);

		verify(notifications, never()).save(any());
	}

	@Test
	void resolvesMentionTokensToDisplayName() {
		User rebar = User.builder().id("u1").displayName("Rebar").build();
		when(users.findById("u1")).thenReturn(Optional.of(rebar));

		assertThat(service.preview("{{user:u1}} hi was geht?")).isEqualTo("@Rebar hi was geht?");
	}

	@Test
	void unknownMentionFallsBackToNeutralName() {
		assertThat(service.preview("{{user:ghost}} hello")).isEqualTo("@someone hello");
	}

	@Test
	void stripsMarkdownAndCollapsesWhitespace() {
		String raw = "## Heading\n\n- **bold** item\n- `code` and [link](https://x.de)";
		assertThat(service.preview(raw)).isEqualTo("Heading bold item code and link");
	}

	@Test
	void truncatesLongTextWithEllipsis() {
		String raw = "x".repeat(500);
		String out = service.preview(raw);
		assertThat(out).hasSize(160).endsWith("…");
	}

	@Test
	void handlesNullAndBlank() {
		assertThat(service.preview(null)).isEmpty();
		assertThat(service.preview("   \n\t ")).isEmpty();
	}
}
