package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleController;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.me.DataExportPdfService;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.media.MediaService;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.StorageBackend;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Frozen content is unreachable through every path that serves it — <b>including
 * for an administrator</b>.
 *
 * <p>That last clause is the whole test. Every other access rule in this codebase
 * lets an admin through, and correctly so: {@code SearchService.Scope} and
 * {@code ArticleVisibility.criteria} both answer {@code null} for one, meaning "no
 * restriction". A freeze is the one mechanism where the admin short-circuit is
 * wrong, so each assertion below is made twice — once for a project member and
 * once for a platform admin — because a guard placed one line after
 * {@code if (user.isAdmin())} passes every member-only test ever written.
 *
 * <p>The second thing under test is <em>where</em> the guards sit. They are in the
 * services rather than the controllers, which is what makes the MCP tools and the
 * e-mail poller inherit them; a controller-level check would leave
 * {@code hinata://issue/{id}} rendering a frozen description.
 */
class FrozenReadPathTest {

	private static final String PROJECT = "p-1";

	private final User member = user("u-member", false);
	private final User admin = user("u-admin", true);

	private IssueRepository issues;
	private IssueCommentRepository comments;
	private StorageBackend backend;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		comments = mock(IssueCommentRepository.class);
		backend = mock(StorageBackend.class);
	}

	// --- issues ----------------------------------------------------------------

	@Test
	void aFrozenIssueIsNotFoundForAMember() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		assertThatThrownBy(() -> service.getForUser("i-1", member))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	/** The one that matters: the account every other rule lets through. */
	@Test
	void aFrozenIssueIsNotFoundForAnAdminToo() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		assertThatThrownBy(() -> service.getForUser("i-1", admin))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	/** And the unfrozen issue beside it still opens — for both. */
	@Test
	void anUnfrozenIssueStillOpens() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		assertThat(service.getForUser("i-2", member).getId()).isEqualTo("i-2");
		assertThat(service.getForUser("i-2", admin).getId()).isEqualTo("i-2");
	}

	/**
	 * The ACL-free internal lookup stays open. It is what the moderation queue
	 * resolves rows through, and a freeze check there would leave the queue unable to
	 * describe the thing it just froze.
	 */
	@Test
	void theInternalLookupIsDeliberatelyNotGuarded() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		assertThatCode(() -> service.get("i-1")).doesNotThrowAnyException();
	}

	// --- comments --------------------------------------------------------------

	@Test
	void aFrozenCommentIsAbsentFromTheFeedForAMemberAndAnAdmin() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));

		assertThat(ids(service.commentsOf("i-2", 0, 25, member))).containsExactly("c-2");
		assertThat(ids(service.commentsOf("i-2", 0, 25, admin))).containsExactly("c-2");
	}

	/**
	 * A frozen comment cannot be edited <b>by its own author</b>. Without this the
	 * author of frozen content overwrites the evidence the freeze exists to preserve
	 * — {@code editComment} checks only that the caller wrote it.
	 */
	@Test
	void aFrozenCommentCannotBeEditedByItsAuthor() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));
		User author = user("u-author", false);

		assertThatThrownBy(() -> service.editComment("i-2", "c-1",
				new com.ahmadre.hinata.richtext.RichText(null, "rewritten", List.of()), author))
				.isInstanceOf(ApiException.class);
		verify(comments, never()).save(any());
	}

	/**
	 * And cannot be deleted <b>by an admin</b>. The exemption {@code requireComment}
	 * documents — "an admin who blocked a user still has to be able to open and
	 * delete that user's comment" — inverts here: for frozen content, deleting is
	 * precisely the thing that must not happen.
	 */
	@Test
	void aFrozenCommentCannotBeDeletedByAnAdmin() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));

		assertThatThrownBy(() -> service.deleteComment("i-2", "c-1", admin))
				.isInstanceOf(ApiException.class);
		verify(comments, never()).delete(any());
	}

	/** Playing back a frozen voice note is a read, and is refused like one. */
	@Test
	void aFrozenVoiceCommentCannotBePlayedBack() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));

		assertThatThrownBy(() -> service.loadVoice("i-2", "c-1", admin))
				.isInstanceOf(ApiException.class);
	}

	// --- bytes -----------------------------------------------------------------

	/**
	 * The single most likely miss, per the design review: {@code /api/v1/media/{id}}
	 * takes no user at all, so freezing the comment that embeds an image does nothing
	 * to the image. The guard is one layer down, at the only chokepoint every byte in
	 * the product passes through.
	 */
	@Test
	void frozenBytesAreUnreachableThroughTheMediaRouteThatHasNoViewer() throws Exception {
		StorageService storage = storage(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.OBJECT, "media/" + MEDIA_ID)));
		MediaService media = new MediaService(storage);

		assertThatThrownBy(() -> media.load(MEDIA_ID))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
		verify(backend, never()).get(any());
	}

	@Test
	void unfrozenBytesAreStillServed() throws Exception {
		StorageService storage = storage(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.OBJECT, "media/other")));
		MediaService media = new MediaService(storage);

		assertThat(media.load(MEDIA_ID)).isNotNull();
	}

	/**
	 * Bytes are never deleted. The orphan sweep, comment deletion and the project
	 * cascade all funnel into {@code storage.delete}, and each of them treats
	 * deletion as best-effort housekeeping — so the refusal is a skip rather than an
	 * exception that would turn a nightly job into a failed one.
	 */
	@Test
	void frozenBytesAreNeverDeleted() throws Exception {
		StorageService storage = storage(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.OBJECT, "media/" + MEDIA_ID)));

		storage.delete("media/" + MEDIA_ID);
		storage.delete("media/other");

		verify(backend, never()).delete("media/" + MEDIA_ID);
		verify(backend).delete("media/other");
	}

	// --- articles ---------------------------------------------------------------

	@Test
	void aFrozenArticleIsNotFoundForAMemberAndAnAdmin() {
		ArticleRepository articles = mock(ArticleRepository.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		ProjectService projects = mock(ProjectService.class);
		TeamService teams = mock(TeamService.class);
		Article article = Article.builder().id("a-1").title("Frozen").build();
		when(articles.findById("a-1")).thenReturn(Optional.of(article));
		when(projects.visibleTo(any())).thenReturn(List.of());
		when(teams.visibleTo(any())).thenReturn(List.of());
		ArticleController controller = new ArticleController(articles, new RichTextService(),
				currentUser, projects, teams, mock(ModerationService.class),
				FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.ARTICLE, "a-1")));

		for (User viewer : List.of(member, admin)) {
			when(currentUser.require()).thenReturn(viewer);
			assertThatThrownBy(() -> controller.get("a-1"))
					.isInstanceOf(ApiException.class)
					.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
							.isEqualTo(HttpStatus.NOT_FOUND));
		}
	}

	// --- the unauthenticated GDPR export ----------------------------------------

	/**
	 * {@code GET /api/v1/me/export.pdf} is a signed link that needs no session and
	 * prints every comment the subject authored verbatim. Without a filter, the
	 * author of frozen content retrieves it by mailing themselves an export.
	 */
	@Test
	void theUnauthenticatedExportDoesNotPrintFrozenContent() {
		User subject = user("u-author", false);

		byte[] unrestricted = exportFor(subject, FreezeFixtures.nothingFrozen());
		byte[] restricted = exportFor(subject, FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1"),
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));

		// The PDF stream is compressed, so "does not contain the string" would be a
		// vacuous assertion. What is asserted instead is that the frozen rows were
		// dropped before rendering: the same subject, the same repositories, one
		// document strictly shorter than the other.
		assertThat(unrestricted).isNotEmpty();
		assertThat(restricted.length).isLessThan(unrestricted.length);
	}

	/** The export for [subject] under one freeze registry. */
	private byte[] exportFor(User subject, FrozenContentService frozen) {
		MeService me = mock(MeService.class);
		IssueRepository exportIssues = mock(IssueRepository.class);
		IssueCommentRepository exportComments = mock(IssueCommentRepository.class);
		when(me.notificationPreferences(subject)).thenReturn(NotificationPreferences.defaults());
		when(me.projectsOf(subject)).thenReturn(List.of());
		when(me.teamsOf("u-author")).thenReturn(List.of());
		when(exportIssues.findByReporterIdOrderByCreatedAtDesc("u-author"))
				.thenReturn(List.of(issue("i-1", "Frozen issue title"),
						issue("i-2", "Ordinary issue title")));
		when(exportIssues.findByAssigneeIdsContainsOrderByCreatedAtDesc("u-author"))
				.thenReturn(List.of());
		when(exportComments.findByAuthorIdOrderByCreatedAtDesc("u-author"))
				.thenReturn(List.of(comment("c-1", "i-2", "u-author", "frozen comment body"),
						comment("c-2", "i-2", "u-author", "ordinary comment body")));
		SessionService sessions = mock(SessionService.class);
		when(sessions.list("u-author")).thenReturn(List.of());
		AuditLogRepository logs = mock(AuditLogRepository.class);
		when(logs.findTop200ByActorIdOrderByTimestampDesc("u-author")).thenReturn(List.of());

		return new DataExportPdfService(me, sessions, exportIssues, exportComments, logs, frozen)
				.build(subject);
	}

	// --- helpers ----------------------------------------------------------------

	private static final String MEDIA_ID = "123e4567-e89b-12d3-a456-426614174000";

	private IssueService issueService(FrozenContentService frozen) {
		Issue frozenIssue = issue("i-1", "Frozen");
		Issue openIssue = issue("i-2", "Open");
		when(issues.findById("i-1")).thenReturn(Optional.of(frozenIssue));
		when(issues.findById("i-2")).thenReturn(Optional.of(openIssue));
		when(comments.findById("c-1"))
				.thenReturn(Optional.of(comment("c-1", "i-2", "u-author", "frozen")));
		when(comments.findById("c-2"))
				.thenReturn(Optional.of(comment("c-2", "i-2", "u-other", "open")));
		when(comments.findByIssueIdAndReplyToIdIsNull(any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(
						List.of(comment("c-1", "i-2", "u-author", "frozen"),
								comment("c-2", "i-2", "u-other", "open"))));
		when(comments.countRepliesGrouped(any())).thenReturn(List.of());

		ProjectService projects = mock(ProjectService.class);
		Project project = Project.builder().id(PROJECT).key("HIN").name("Hinata")
				.memberIds(new ArrayList<>(List.of("u-member", "u-author", "u-other"))).build();
		when(projects.get(PROJECT)).thenReturn(project);
		when(projects.visibleTo(any())).thenReturn(List.of(project));

		com.ahmadre.hinata.moderation.report.UserBlockService blocks =
				mock(com.ahmadre.hinata.moderation.report.UserBlockService.class);
		when(blocks.blockedBy(any())).thenReturn(Set.of());

		org.springframework.data.mongodb.core.MongoTemplate mongo =
				mock(org.springframework.data.mongodb.core.MongoTemplate.class);
		when(mongo.find(any(), any())).thenReturn(List.of(comment("c-2", "i-2", "u-other", "open")));
		when(mongo.count(any(), (Class<?>) any())).thenReturn(1L);

		return new IssueService(issues, comments,
				mock(com.ahmadre.hinata.issue.IssueActivityRepository.class),
				mock(com.ahmadre.hinata.issue.IssueLinkRepository.class),
				mock(com.ahmadre.hinata.issue.IssueLinkEvents.class),
				mock(com.ahmadre.hinata.issue.CommentEvents.class), projects,
				mock(com.ahmadre.hinata.notification.NotificationService.class),
				mock(StorageService.class),
				mock(com.ahmadre.hinata.timetracking.WorkItemRepository.class),
				mock(com.ahmadre.hinata.audit.AuditService.class, RETURNS_DEEP_STUBS), mongo,
				mock(com.ahmadre.hinata.board.AgileBoardRepository.class),
				mock(com.ahmadre.hinata.board.SprintRepository.class),
				mock(UserRepository.class), mock(ModerationService.class),
				mock(com.ahmadre.hinata.moderation.ModerationRecorder.class), blocks, frozen);
	}

	/**
	 * A {@link StorageService} with a stub backend.
	 *
	 * <p>The backend is injected reflectively because it is chosen in the constructor
	 * from configuration and there is no seam for one — which is fine for the thing
	 * under test here: the guard runs before the backend is ever consulted, and the
	 * assertion is precisely that the backend is never reached.
	 */
	private StorageService storage(FrozenContentService frozen) throws Exception {
		HinataProperties properties = new HinataProperties();
		properties.getStorage().setProvider("s3");
		properties.getStorage().setAccessKey("key");
		properties.getStorage().setSecretKey("secret");
		properties.getStorage().setEndpoint("http://localhost:9000");
		StorageService service = new StorageService(properties, null, null, frozen);
		when(backend.get(any())).thenReturn(Optional.of(
				new StorageService.StoredObject(new byte[] { 1 }, "image/png")));
		ReflectionTestUtils.setField(service, "backend", backend);
		return service;
	}

	private static List<String> ids(org.springframework.data.domain.Page<IssueComment> page) {
		return page.getContent().stream().map(IssueComment::getId).toList();
	}

	private static Issue issue(String id, String title) {
		return Issue.builder().id(id).projectId(PROJECT).title(title).readableId("HIN-" + id)
				.reporterId("u-author").createdAt(Instant.now()).attachments(new ArrayList<>()).build();
	}

	private static IssueComment comment(String id, String issueId, String authorId, String text) {
		return IssueComment.builder().id(id).issueId(issueId).authorId(authorId).text(text)
				.createdAt(Instant.now()).build();
	}

	private static User user(String id, boolean isAdmin) {
		return User.builder().id(id).displayName(id).email(id + "@example.org").active(true)
				.roles(Set.of(isAdmin ? Role.ADMIN : Role.MEMBER)).build();
	}
}
