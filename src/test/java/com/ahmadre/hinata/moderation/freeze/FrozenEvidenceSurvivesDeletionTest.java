package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.deletion.DeletionService;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.AttachmentController;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
 * Frozen content survives every destructive path in the product.
 *
 * <p>{@link FrozenContent}'s own javadoc has always promised this — "a row here is
 * what lets the deletion and garbage-collection paths <em>refuse</em>" — and until
 * now nothing kept it. {@code IssueService.delete} resolved through the ACL-free
 * {@code get} rather than {@code getForUser}, so no freeze check ran at all, and
 * one call from an admin or a project lead destroyed a frozen issue along with
 * every frozen comment on it. The project cascade was worse: it deletes every
 * issue, comment, activity row, attachment and article of a whole project and
 * asked nothing.
 *
 * <p>Refuse, not skip. A cascade that stepped over the frozen row would delete
 * everything around it and leave a comment with no thread — evidence that
 * technically survives and cannot be read in context, which is not what a
 * preservation obligation means.
 *
 * <p>Half of these assert that ordinary deletion still works, because a guard that
 * refuses everything is also "safe" and would break the product for every install
 * that has nothing frozen.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenEvidenceSurvivesDeletionTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String PROJECT = "p-1";

	private final User admin = User.builder().id("u-admin").displayName("Ada").active(true)
			.roles(Set.of(Role.ADMIN)).build();

	private MongoTemplate mongo;
	private IssueRepository issues;
	private IssueCommentRepository comments;
	private ProjectService projects;
	private Project project;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-delete");
		mongo.getDb().drop();

		project = Project.builder().id(PROJECT).key("HIN").name("Hinata").archived(false)
				.memberIds(new ArrayList<>(List.of("u-admin"))).build();
		projects = mock(ProjectService.class);
		when(projects.get(PROJECT)).thenReturn(project);
		when(projects.canDeleteIssues(any(), any())).thenReturn(true);
		when(projects.visibleTo(any())).thenReturn(List.of(project));

		issues = mock(IssueRepository.class);
		comments = mock(IssueCommentRepository.class);
		when(issues.findByParentId(any())).thenReturn(List.of());
	}

	// --- one issue ------------------------------------------------------------------

	@Test
	void anAdminCannotHardDeleteAFrozenIssue() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")), issue("i-1"));

		assertThatThrownBy(() -> service.delete("i-1", admin))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(issues, never()).delete(any());
	}

	/**
	 * The blast radius, not just the target. A frozen comment under an unfrozen issue
	 * is destroyed by {@code comments.deleteByIssueId} — deleting the parent is how a
	 * guard on the child alone gets walked around.
	 */
	@Test
	void deletingAnIssueIsRefusedWhenOneOfItsCommentsIsFrozen() {
		mongo.save(comment("c-frozen", "i-1"));
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-frozen")), issue("i-1"));

		assertThatThrownBy(() -> service.delete("i-1", admin)).isInstanceOf(ApiException.class);
		verify(comments, never()).deleteByIssueId(any());
	}

	/** Sub-tasks are cascade-deleted with their parent, so a frozen one blocks it. */
	@Test
	void deletingAParentIsRefusedWhenASubtaskIsFrozen() {
		Issue parent = issue("i-1");
		when(issues.findByParentId("i-1")).thenReturn(List.of(issue("i-sub")));
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-sub")), parent);

		assertThatThrownBy(() -> service.delete("i-1", admin)).isInstanceOf(ApiException.class);
		verify(issues, never()).delete(any());
	}

	/** A frozen attachment's bytes go with the issue, so the issue is held too. */
	@Test
	void deletingAnIssueIsRefusedWhenOneOfItsAttachmentsIsFrozen() {
		Issue withFile = issue("i-1");
		withFile.getAttachments().add(Issue.Attachment.builder().id("a-1")
				.objectKey("attachments/held.png").fileName("held.png")
				.uploadedAt(Instant.now()).build());
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.OBJECT, "attachments/held.png")), withFile);

		assertThatThrownBy(() -> service.delete("i-1", admin)).isInstanceOf(ApiException.class);
		verify(issues, never()).delete(any());
	}

	@Test
	void anOrdinaryIssueIsStillDeletable() {
		IssueService service = issueService(FreezeFixtures.nothingFrozen(), issue("i-1"));

		assertThatCode(() -> service.delete("i-1", admin)).doesNotThrowAnyException();
		verify(issues).delete(any());
	}

	/**
	 * Archiving is not refused for destroying evidence — it destroys none. It is
	 * refused because {@code setArchived} used to resolve through the ACL-free
	 * {@code get}, so a member who got 404 opening an issue and 200 archiving the
	 * same id had just confirmed that it exists and is restricted. A frozen issue has
	 * to look deleted on writes as well as reads.
	 */
	@Test
	void archivingAFrozenIssueAnswersNotFoundRatherThanSucceeding() {
		IssueService service = issueService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")), issue("i-1"));

		assertThatThrownBy(() -> service.setArchived("i-1", true, admin))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
		verify(issues, never()).save(any());
	}

	// --- the project cascade ---------------------------------------------------------

	@Test
	void aProjectCascadeRefusesWhenOneOfItsIssuesIsFrozen() {
		mongo.save(issue("i-1"));
		DeletionService deletion = deletionService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		cascade(deletion, DeletionService.IssueStrategy.DELETE);

		assertThat(mongo.findById("i-1", Issue.class))
				.as("the whole cascade stops before its first write")
				.isNotNull();
	}

	@Test
	void aProjectCascadeRefusesWhenOneOfItsCommentsIsFrozen() {
		mongo.save(issue("i-1"));
		mongo.save(comment("c-1", "i-1"));
		DeletionService deletion = deletionService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.COMMENT, "c-1")));

		cascade(deletion, DeletionService.IssueStrategy.DELETE);

		assertThat(mongo.findById("c-1", IssueComment.class)).isNotNull();
		assertThat(mongo.findById("i-1", Issue.class)).isNotNull();
	}

	@Test
	void aProjectCascadeRefusesWhenOneOfItsArticlesIsFrozen() {
		mongo.save(Article.builder().id("a-1").projectId(PROJECT).title("Held").build());
		DeletionService deletion = deletionService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ARTICLE, "a-1")));

		cascade(deletion, DeletionService.IssueStrategy.NONE);

		assertThat(mongo.findById("a-1", Article.class)).isNotNull();
	}

	/**
	 * Migration moves the issues rather than removing them, so their freezes travel
	 * with them. Refusing here would block the one strategy that preserves the thing
	 * the refusal exists to preserve.
	 */
	@Test
	void migratingIssuesOutOfAProjectIsNotRefusedByAFrozenIssue() {
		mongo.save(issue("i-1"));
		DeletionService deletion = deletionService(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-1")));

		cascade(deletion, DeletionService.IssueStrategy.MIGRATE);

		assertThat(mongo.findById("i-1", Issue.class).getProjectId()).isEqualTo("p-2");
	}

	@Test
	void anOrdinaryProjectIsStillDeletable() {
		mongo.save(issue("i-1"));
		DeletionService deletion = deletionService(FreezeFixtures.nothingFrozen());

		cascade(deletion, DeletionService.IssueStrategy.DELETE);

		assertThat(mongo.findById("i-1", Issue.class)).isNull();
	}

	// --- helpers ----------------------------------------------------------------------

	// --- the attachment route -------------------------------------------------------

	/**
	 * Deleting a frozen attachment is refused, and neither half of it happens.
	 *
	 * <p>The byte guard inside {@code StorageService.delete} was doing its job and
	 * that was the problem: it skipped the object, so the bytes survived, while
	 * {@code AttachmentStore.remove} had already pulled the subdocument — fileName,
	 * size, uploader, uploadedAt and the objectKey itself — out of
	 * {@code Issue.attachments}. The outcome was a file in the bucket with nothing in
	 * the database pointing at it: evidence that technically exists and is
	 * practically unfindable, which is exactly what the last line of defence is not
	 * supposed to leave behind.
	 *
	 * <p>Asserted as a 409 and as two calls that never happen, because a refusal that
	 * arrives after the metadata is gone is not a refusal.
	 */
	@Test
	void deletingAFrozenAttachmentIsRefusedAndLeavesTheMetadataIntact() {
		AttachmentStore store = mock(AttachmentStore.class);
		StorageService storage = mock(StorageService.class);

		assertThatThrownBy(() -> attachments(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ATTACHMENT, "a-1")), store, storage)
				.delete("i-1", "a-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(store, never()).remove(any(), any());
		verify(storage, never()).delete(any());
	}

	/**
	 * And when the freeze names the bytes rather than the attachment — an issue, a
	 * comment or an inline image whose resolution produced this object key. A
	 * moderator who froze the parent must not be left with the one destructive route
	 * to its files still open.
	 */
	@Test
	void deletingAnAttachmentWhoseBytesAreFrozenIsRefusedToo() {
		AttachmentStore store = mock(AttachmentStore.class);
		StorageService storage = mock(StorageService.class);

		assertThatThrownBy(() -> attachments(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.OBJECT, "attachments/one.png")), store, storage)
				.delete("i-1", "a-1"))
				.isInstanceOf(ApiException.class);
		verify(store, never()).remove(any(), any());
	}

	/** An ordinary attachment is still deletable, both halves of it. */
	@Test
	void anOrdinaryAttachmentIsStillDeletable() {
		AttachmentStore store = mock(AttachmentStore.class);
		StorageService storage = mock(StorageService.class);
		when(store.remove(any(), any())).thenReturn(issueWithAttachment());

		attachments(FreezeFixtures.nothingFrozen(), store, storage).delete("i-1", "a-1");

		verify(store).remove("i-1", "a-1");
		verify(storage).delete("attachments/one.png");
	}

	private AttachmentController attachments(FrozenContentService frozen, AttachmentStore store,
			StorageService storage) {
		IssueService issueService = mock(IssueService.class);
		when(issueService.getForUser(any(), any())).thenReturn(issueWithAttachment());
		com.ahmadre.hinata.auth.CurrentUser currentUser =
				mock(com.ahmadre.hinata.auth.CurrentUser.class);
		when(currentUser.require()).thenReturn(admin);
		return new AttachmentController(issueService, storage, store,
				mock(com.ahmadre.hinata.storage.AttachmentEvents.class), frozen, currentUser);
	}

	private static Issue issueWithAttachment() {
		Issue issue = issue("i-1");
		issue.getAttachments().add(Issue.Attachment.builder().id("a-1")
				.fileName("one.png").objectKey("attachments/one.png")
				.uploaderId("u-author").uploadedAt(Instant.now()).build());
		return issue;
	}

	/**
	 * Runs the cascade through its real entry point.
	 *
	 * <p>{@code @Async} is a proxy concern, so on a plain instance this executes on
	 * the calling thread — and it goes through the same {@code catch (ApiException)}
	 * that turns a refusal into a localized {@code error} on the progress stream. The
	 * refusal is therefore asserted the way it actually manifests: the content is
	 * still there afterwards. Reaching for the private method instead would prove the
	 * guard exists without proving the cascade consults it.
	 */
	private void cascade(DeletionService deletion, DeletionService.IssueStrategy strategy) {
		Project target = strategy == DeletionService.IssueStrategy.MIGRATE
				? Project.builder().id("p-2").key("TWO").name("Two").build()
				: null;
		deletion.deleteProject(project,
				new DeletionService.ProjectDeleteOptions(strategy, target, 1),
				java.util.Locale.ENGLISH, mock(SseEmitter.class));
	}

	/**
	 * The real message bundle, not a stub returning null.
	 *
	 * <p>The refusal travels to the client as a localized {@code error} event, so a
	 * MessageSource that cannot resolve the key turns the refusal into an unrelated
	 * NPE — which passes a "the data survived" assertion for entirely the wrong
	 * reason. Loading the real bundle also means the key has to exist in
	 * {@code messages.properties} for these tests to be meaningful.
	 */
	private static org.springframework.context.MessageSource messages() {
		org.springframework.context.support.ResourceBundleMessageSource source =
				new org.springframework.context.support.ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		source.setFallbackToSystemLocale(false);
		return source;
	}

	private DeletionService deletionService(FrozenContentService frozen) {
		ProjectRepository projectRepo = mock(ProjectRepository.class);
		IssueRepository issueRepo = mock(IssueRepository.class);
		when(issueRepo.findByProjectId(any(), any())).thenAnswer(call ->
				new PageImpl<>(mongo.findAll(Issue.class)));
		ProjectService projectService = mock(ProjectService.class);
		when(projectService.nextIssueNumber(any())).thenReturn(1L);
		return new DeletionService(mock(com.ahmadre.hinata.board.AgileBoardRepository.class),
				mock(com.ahmadre.hinata.board.SprintRepository.class), issueRepo, projectRepo,
				projectService, mock(com.ahmadre.hinata.team.TeamRepository.class),
				mock(com.ahmadre.hinata.team.TeamActivityRepository.class),
				mock(StorageService.class), mongo,
				messages(), frozen);
	}

	private IssueService issueService(FrozenContentService frozen, Issue target) {
		when(issues.findById(target.getId())).thenReturn(Optional.of(target));
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
				mock(com.ahmadre.hinata.moderation.ModerationRecorder.class),
				mock(com.ahmadre.hinata.moderation.report.UserBlockService.class), frozen);
	}

	private static Issue issue(String id) {
		return Issue.builder().id(id).projectId(PROJECT).title("Issue " + id)
				.readableId("HIN-" + id.toUpperCase(java.util.Locale.ROOT))
				.type(Issue.Type.TASK).state("Open").archived(false).numberInProject(1)
				.createdAt(Instant.now()).attachments(new ArrayList<>()).build();
	}

	private static IssueComment comment(String id, String issueId) {
		return IssueComment.builder().id(id).issueId(issueId).authorId("u-author").text("text")
				.createdAt(Instant.now()).build();
	}
}
