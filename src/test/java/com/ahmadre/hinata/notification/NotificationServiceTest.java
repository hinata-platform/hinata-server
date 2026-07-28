package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
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

/**
 * Covers the plain-text teaser and the reply-notification fan-out. The teaser
 * now reads a Lexical document rather than a markdown string, so these use a
 * real {@link RichTextService} and exercise the whole chain — markdown in,
 * document stored, teaser out — rather than a stubbed middle.
 */
class NotificationServiceTest {

	private UserRepository users;
	private NotificationRepository notifications;
	private RichTextService richText;
	private NotificationService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		notifications = mock(NotificationRepository.class);
		richText = new RichTextService();
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		service = new NotificationService(notifications, users,
				mock(MailService.class), mock(PushService.class), mock(GatewayService.class),
				richText);
	}

	/** A stored text comment, as the write path would have produced it. */
	private IssueComment comment(String id, String authorId, String markdown, String replyToAuthorId) {
		RichText content = richText.fromMarkdown(markdown);
		return IssueComment.builder().id(id).issueId("i1").authorId(authorId)
				.text(content.text()).textDoc(content.doc())
				.replyToId("root").replyToAuthorId(replyToAuthorId).build();
	}

	private Issue issue() {
		return Issue.builder().id("i1").readableId("MOB-9").title("Login bug")
				.watcherIds(new ArrayList<>()).build();
	}

	/** The teaser for content authored as markdown. */
	private String previewOf(String markdown) {
		return service.preview(richText.fromMarkdown(markdown).doc());
	}

	// --- fan-out --------------------------------------------------------------

	@Test
	void replyNotifiesParentAuthorWithAnchoredCommentLink() {
		User replier = User.builder().id("u2").displayName("Sam").active(true).build();
		User parentAuthor = User.builder().id("u1").displayName("Rebar").active(true).build();
		when(users.findById("u1")).thenReturn(Optional.of(parentAuthor));

		service.notifyComment(issue(), replier, comment("c99", "u2", "good catch", "u1"));

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
		// The reply both @mentions and answers u1 — they must be pinged exactly once.

		service.notifyComment(issue(), replier, comment("c99", "u2", "{{user:u1}} thanks", "u1"));

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getAllValues()).singleElement().satisfies(
				n -> assertThat(n.getType()).isEqualTo(Notification.Type.MENTION));
	}

	@Test
	void selfReplyDoesNotNotify() {
		User author = User.builder().id("u1").displayName("Rebar").active(true).build();

		service.notifyComment(issue(), author, comment("c99", "u1", "adding more", "u1"));

		verify(notifications, never()).save(any());
	}

	@Test
	void voiceCommentWithoutADocumentStillNotifies() {
		// A voice comment has no text and no document; the fan-out must fall back to
		// the generic wording rather than throwing on a null document.
		User replier = User.builder().id("u2").displayName("Sam").active(true).build();
		when(users.findById("u1")).thenReturn(
				Optional.of(User.builder().id("u1").displayName("Rebar").active(true).build()));
		IssueComment voice = IssueComment.builder().id("c1").issueId("i1").authorId("u2")
				.type(IssueComment.Type.VOICE).replyToId("root").replyToAuthorId("u1").build();

		service.notifyComment(issue(), replier, voice);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getValue().getBody()).contains("Login bug");
	}

	// --- description mentions -------------------------------------------------

	/**
	 * The whole point of {@code notifyNewMentions} is the word "new": dragging an
	 * issue to another column, renaming it or changing its priority must not ping
	 * everyone its description happens to name. This is where a caller passing a
	 * {@code before} that is always null turns an issue with five mentions into
	 * five notifications, five pushes and five e-mails on every unrelated edit.
	 */
	@Test
	void anUnrelatedEditDoesNotRenotifyExistingMentions() {
		User editor = User.builder().id("u9").displayName("Sam").active(true).build();
		String description = richText.fromMarkdown(
				"Betrifft {{user:u1}}, {{user:u2}} und {{user:u3}}.").doc();

		// The description did not change; only some other field did.
		service.notifyNewMentions(issue(), editor, description, description);

		verify(notifications, never()).save(any());
	}

	@Test
	void onlyTheMentionAddedByThisEditIsNotified() {
		User editor = User.builder().id("u9").displayName("Sam").active(true).build();
		when(users.findById("u2")).thenReturn(
				Optional.of(User.builder().id("u2").displayName("Nora").active(true).build()));
		String before = richText.fromMarkdown("Betrifft {{user:u1}}.").doc();
		String after = richText.fromMarkdown("Betrifft {{user:u1}} und {{user:u2}}.").doc();

		service.notifyNewMentions(issue(), editor, before, after);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getAllValues()).singleElement().satisfies(n -> {
			assertThat(n.getType()).isEqualTo(Notification.Type.MENTION);
			assertThat(n.getUserId()).isEqualTo("u2");
		});
	}

	@Test
	void aFirstDescriptionNotifiesEveryoneItMentions() {
		User editor = User.builder().id("u9").displayName("Sam").active(true).build();
		when(users.findById("u1")).thenReturn(
				Optional.of(User.builder().id("u1").displayName("Rebar").active(true).build()));
		String after = richText.fromMarkdown("Bitte {{user:u1}} ansehen.").doc();

		service.notifyNewMentions(issue(), editor, null, after);

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo("u1");
	}

	// --- teaser ---------------------------------------------------------------

	@Test
	void resolvesMentionsToDisplayName() {
		User rebar = User.builder().id("u1").displayName("Rebar").build();
		when(users.findById("u1")).thenReturn(Optional.of(rebar));

		assertThat(previewOf("{{user:u1}} hi was geht?")).isEqualTo("@Rebar hi was geht?");
	}

	@Test
	void unknownMentionFallsBackToNeutralName() {
		assertThat(previewOf("{{user:ghost}} hello")).isEqualTo("@someone hello");
	}

	@Test
	void formattingIsGoneAndWhitespaceCollapsed() {
		// No stripping regexes any more: there is no syntax in a document to strip.
		String raw = "## Heading\n\n- **bold** item\n- `code` and [link](https://x.de)";

		assertThat(previewOf(raw)).isEqualTo("Heading bold item code and link");
	}

	@Test
	void nestedFormattingThatTheOldStripperGotWrongNowReadsCleanly() {
		assertThat(previewOf("**fett mit `code` drin**")).isEqualTo("fett mit code drin");
	}

	@Test
	void truncatesLongTextWithEllipsis() {
		String out = previewOf("x".repeat(500));

		assertThat(out).hasSize(160).endsWith("…");
	}

	@Test
	void handlesNullAndBlank() {
		assertThat(service.preview(null)).isEmpty();
		assertThat(service.preview("")).isEmpty();
		assertThat(previewOf("   \n\t ")).isEmpty();
	}

	@Test
	void unreadableDocumentDoesNotBreakTheTeaser() {
		// A stored document that cannot be parsed must degrade to no teaser, not to
		// a failed notification that rolls back a saved comment.
		assertThat(service.preview("{ not json")).isEmpty();
	}
}
