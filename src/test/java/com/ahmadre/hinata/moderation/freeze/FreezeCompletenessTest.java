package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.moderation.AdminModerationController;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationQueueService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A freeze reaches everything it claims to: every replica, every byte the target
 * owns, and the compliance field that records what was not done.
 *
 * <p>Three separate holes, one theme. Each of them left a freeze that <em>looked</em>
 * complete — a row in the registry, an audit entry, a 404 on the detail view — while
 * the material it named was still being served, from another container, from an
 * attachment URL, or from an inline image inside the frozen body itself.
 */
class FreezeCompletenessTest {

	private FrozenContentRepository registry;
	private IssueRepository issues;
	private IssueCommentRepository comments;
	private ArticleRepository articles;
	private FrozenContentService service;

	private final List<FrozenContent> stored = new ArrayList<>();
	private final User admin = User.builder().id("u-admin").displayName("Ada")
			.roles(Set.of(Role.ADMIN)).build();

	@BeforeEach
	void setUp() {
		registry = mock(FrozenContentRepository.class);
		issues = mock(IssueRepository.class);
		comments = mock(IssueCommentRepository.class);
		articles = mock(ArticleRepository.class);
		when(registry.findByUnfrozenAtIsNull()).thenReturn(List.of());
		when(registry.findByTargetTypeAndTargetId(any(), any())).thenReturn(Optional.empty());
		when(registry.save(any())).thenAnswer(call -> {
			FrozenContent row = call.getArgument(0);
			stored.add(row);
			doReturn(List.copyOf(stored)).when(registry).findByUnfrozenAtIsNull();
			return row;
		});
		service = new FrozenContentService(registry, mock(AuditService.class, RETURNS_DEEP_STUBS),
				new FrozenObjectKeys(issues, comments, articles));
		service.refresh();
	}

	// --- gap 1: the snapshot behind more than one replica --------------------------

	/**
	 * A freeze raised on another instance has to arrive on this one without a
	 * restart.
	 *
	 * <p>{@code refresh()} ran on {@code ApplicationReadyEvent} and after a
	 * <em>local</em> write, and nowhere else. Behind two replicas that means instance
	 * B enforces nothing that instance A froze, for the life of the process — which
	 * for a Portainer or Compose deployment with a scaled service is the feature not
	 * working at all.
	 *
	 * <p>The poll is found by its annotation rather than called by name, because the
	 * mistake this guards against is "nobody scheduled it": a test that calls
	 * {@code refreshPeriodically()} directly passes whether or not anything ever
	 * invokes it in production.
	 */
	@Test
	void aFreezeFromAnotherInstanceArrivesOnThisOneWithoutARestart() throws Exception {
		Method poll = scheduledPoll();
		assertThat(poll)
				.as("no @Scheduled method on FrozenContentService — a freeze raised on another "
						+ "replica would never be enforced here")
				.isNotNull();
		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-elsewhere")).isFalse();

		// What another container just wrote to the shared database.
		doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-elsewhere")))
				.when(registry).findByUnfrozenAtIsNull();
		poll.invoke(service);

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-elsewhere")).isTrue();
	}

	/**
	 * And the interval is configurable, because it is the exposure window an operator
	 * has to be able to reason about — see {@code HinataProperties.Moderation}. A
	 * hard-coded delay would make the bound undocumentable and untunable at once.
	 */
	@Test
	void thePollIntervalComesFromTheDocumentedProperty() {
		Scheduled scheduled = scheduledPoll().getAnnotation(Scheduled.class);

		assertThat(scheduled.fixedDelayString())
				.as("the poll must be tunable through the property whose javadoc states the bound")
				.contains("hinata.moderation.freeze-refresh-interval");
		assertThat(new com.ahmadre.hinata.config.HinataProperties()
				.getModeration().getFreezeRefreshInterval())
				.isEqualTo(java.time.Duration.ofSeconds(30));
	}

	// --- gaps 3 + 4: the bytes a freeze covers --------------------------------------

	/**
	 * Freezing an issue by hand froze the row and left every attachment downloadable:
	 * {@code AdminModerationController} passed {@code List.of()} for the object keys,
	 * so the {@code OBJECT} rows the byte guard matches on were never written.
	 */
	@Test
	void freezingAnIssueReachesItsAttachments() {
		when(issues.findById("i-1")).thenReturn(Optional.of(Issue.builder().id("i-1")
				.attachments(new ArrayList<>(List.of(attachment("a-1", "attachments/one.png"))))
				.build()));

		service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));

		assertThat(service.isFrozenObject("attachments/one.png")).isTrue();
	}

	/**
	 * And its inline images. {@code FrozenContent.objectKeys} claimed all along to
	 * hold "an inline image embedded in the frozen body" and nothing extracted them —
	 * so a frozen issue whose description embeds {@code /api/v1/media/{id}} left those
	 * bytes on a route that takes no viewer at all.
	 */
	@Test
	void freezingAnIssueReachesTheImagesEmbeddedInItsBody() {
		String id = "123e4567-e89b-12d3-a456-426614174000";
		when(issues.findById("i-1")).thenReturn(Optional.of(Issue.builder().id("i-1")
				.attachments(new ArrayList<>())
				.descriptionDoc("{\"src\":\"/api/v1/media/" + id + "\"}")
				.build()));

		service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));

		assertThat(service.isFrozenObject("media/" + id)).isTrue();
	}

	/** The same for a comment, whose body is the more common place to paste one. */
	@Test
	void freezingACommentReachesItsVoiceBlobAndItsInlineImages() {
		String id = "123e4567-e89b-12d3-a456-426614174001";
		IssueComment comment = IssueComment.builder().id("c-1").issueId("i-1")
				.textDoc("![x](/api/v1/media/" + id + ")")
				.voice(IssueComment.Voice.builder().objectKey("voice/c-1.m4a").build())
				.build();
		when(comments.findById("c-1")).thenReturn(Optional.of(comment));

		service.freeze(request(FrozenTargetType.COMMENT, "c-1", "i-1"));

		assertThat(service.isFrozenObject("voice/c-1.m4a")).isTrue();
		assertThat(service.isFrozenObject("media/" + id)).isTrue();
	}

	@Test
	void freezingAnArticleReachesTheImagesInsideIt() {
		String id = "123e4567-e89b-12d3-a456-426614174002";
		when(articles.findById("a-1")).thenReturn(Optional.of(Article.builder().id("a-1")
				.contentDoc("/api/v1/media/" + id).build()));

		service.freeze(request(FrozenTargetType.ARTICLE, "a-1", null));

		assertThat(service.isFrozenObject("media/" + id)).isTrue();
	}

	/**
	 * {@code FrozenTargetType.ATTACHMENT} was checked from no path and given no keys,
	 * which made a hand-freeze of one a row and nothing else — the file stayed
	 * downloadable through the route the reporter had just linked to.
	 */
	@Test
	void freezingOneAttachmentReachesItsBytesAndLeavesTheOthersAlone() {
		when(issues.findById("i-1")).thenReturn(Optional.of(Issue.builder().id("i-1")
				.attachments(new ArrayList<>(List.of(
						attachment("a-1", "attachments/reported.png"),
						attachment("a-2", "attachments/unrelated.png"))))
				.build()));

		service.freeze(request(FrozenTargetType.ATTACHMENT, "a-1", "i-1"));

		assertThat(service.isFrozenObject("attachments/reported.png")).isTrue();
		assertThat(service.isFrozenObject("attachments/unrelated.png"))
				.as("an attachment is a target of its own so one file can be restricted without "
						+ "taking the whole ticket offline")
				.isFalse();
	}

	/** Freezing an account has to reach the avatar, which is served unauthenticated. */
	@Test
	void freezingAUserReachesTheirAvatarBytes() {
		service.freeze(request(FrozenTargetType.USER, "u-1", null));

		assertThat(service.isFrozenObject(AvatarService.objectKeyFor("u-1"))).isTrue();
	}

	/**
	 * Re-freezing widens rather than no-ops. An operator freezing the same issue
	 * again is usually doing it because something was missed since — and the old
	 * code returned the standing row untouched.
	 */
	@Test
	void freezingAgainPicksUpObjectsAddedSince() {
		Issue issue = Issue.builder().id("i-1").attachments(new ArrayList<>()).build();
		when(issues.findById("i-1")).thenReturn(Optional.of(issue));
		service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));

		issue.getAttachments().add(attachment("a-late", "attachments/added-later.png"));
		service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));

		assertThat(service.isFrozenObject("attachments/added-later.png")).isTrue();
	}

	// --- gap 6: the compliance field ------------------------------------------------

	/**
	 * No DSA Art. 17 statement of reasons is issued for a freeze in this product, and
	 * the record of that is the <em>absence</em> of a timestamp.
	 *
	 * <p>The field this replaces was {@code boolean statementWithheld = true} — a
	 * constant asserting a fact about every freeze, which is indistinguishable from a
	 * field nobody had thought about and impossible to make true again the day a
	 * notice path exists. A nullable {@code statementIssuedAt} cannot be wrong in that
	 * way: nothing has to write it for "none was issued" to be the honest answer, and
	 * a statement that <em>is</em> issued has somewhere to be recorded.
	 *
	 * <p>Asserted off a row the freeze path wrote and off the persisted copy, not off
	 * a fresh builder — the same reason as before, since a default passes either way.
	 */
	@Test
	void everyFreezeRecordsThatNoStatementOfReasonsWasIssued() {
		when(issues.findById("i-1"))
				.thenReturn(Optional.of(Issue.builder().id("i-1").attachments(new ArrayList<>()).build()));

		FrozenContent row = service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));

		assertThat(row.getStatementIssuedAt()).isNull();
		assertThat(stored)
				.as("the persisted rows carry it too, not just the object handed back")
				.allMatch(each -> each.getStatementIssuedAt() == null);
	}

	/**
	 * And a freeze that <em>is</em> given a notice can say so, which is the property a
	 * hard-coded {@code true} made unrepresentable.
	 *
	 * <p>This is not speculative API design. The field's entire justification is that
	 * the Art. 17 obligation is owed and undischarged; a field that cannot record the
	 * discharge is a field that can only ever mean one thing, and a value that can only
	 * mean one thing is a constant with extra steps. Reviving a released row clears it
	 * for the same reason: the new restriction has had no notice of its own.
	 */
	@Test
	void aStatementThatWasIssuedCanBeRecorded() {
		when(issues.findById("i-1"))
				.thenReturn(Optional.of(Issue.builder().id("i-1").attachments(new ArrayList<>()).build()));
		FrozenContent row = service.freeze(request(FrozenTargetType.ISSUE, "i-1", null));
		Instant issued = Instant.parse("2026-01-02T03:04:05Z");

		row.setStatementIssuedAt(issued);

		assertThat(row.getStatementIssuedAt()).isEqualTo(issued);
	}

	// --- gap 7: a freeze that could not reach anything -------------------------------

	/**
	 * Freezing an attachment without naming its issue is refused (400), not accepted
	 * and warned about.
	 *
	 * <p>An attachment is a subdocument and cannot be located by id alone, so without
	 * the context {@code FrozenObjectKeys.ofAttachment} finds no bytes: the freeze
	 * wrote its row, answered 200, appeared in the queue with everything an operator
	 * would check — and the reported file stayed downloadable through the very URL the
	 * reporter had linked to. A log line is not a control; the operator never sees it,
	 * and they have no reason to doubt a 200.
	 *
	 * <p>{@code ATTACHMENT} only. Every other kind is locatable by id, so requiring
	 * the context there would refuse freezes that would have worked.
	 */
	@Test
	void freezingAnAttachmentWithoutItsIssueIsRefusedRatherThanSilentlyProtectingNothing() {
		assertThatThrownBy(() -> admin().freeze(new AdminModerationController.FreezeRequest(
				FrozenTargetType.ATTACHMENT, "a-1", null, "by hand")))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.BAD_REQUEST));
		assertThat(stored).as("nothing may be written when the freeze could not reach the file")
				.isEmpty();
	}

	@Test
	void freezingAnAttachmentWithItsIssueIsAccepted() {
		when(issues.findById("i-1")).thenReturn(Optional.of(Issue.builder().id("i-1")
				.attachments(new ArrayList<>(List.of(attachment("a-1", "attachments/one.png"))))
				.build()));

		admin().freeze(new AdminModerationController.FreezeRequest(
				FrozenTargetType.ATTACHMENT, "a-1", "i-1", "by hand"));

		assertThat(service.isFrozenObject("attachments/one.png")).isTrue();
	}

	/** And every other kind still freezes without one. */
	@Test
	void freezingAnIssueStillNeedsNoContext() {
		when(issues.findById("i-1")).thenReturn(Optional.of(
				Issue.builder().id("i-1").attachments(new ArrayList<>()).build()));

		admin().freeze(new AdminModerationController.FreezeRequest(
				FrozenTargetType.ISSUE, "i-1", null, "by hand"));

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-1")).isTrue();
	}

	private AdminModerationController admin() {
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(admin);
		return new AdminModerationController(mock(ModerationQueueService.class), service, currentUser);
	}

	// --- helpers ---------------------------------------------------------------------

	private static Method scheduledPoll() {
		return Stream.of(FrozenContentService.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(Scheduled.class))
				.findFirst()
				.orElse(null);
	}

	private FrozenContentService.Request request(FrozenTargetType type, String id, String context) {
		return new FrozenContentService.Request(type, id, context, List.of(),
				ModerationCategory.SEXUAL_MINORS, null, null, admin, "by hand");
	}

	private static Issue.Attachment attachment(String id, String objectKey) {
		return Issue.Attachment.builder().id(id).objectKey(objectKey).fileName(id + ".png")
				.uploadedAt(Instant.now()).build();
	}
}
