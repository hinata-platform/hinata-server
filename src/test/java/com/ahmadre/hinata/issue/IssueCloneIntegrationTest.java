package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloning an issue against a real MongoDB.
 *
 * <p>Almost everything worth asserting about a clone is a property of the write
 * path rather than of the object built in memory: whether the copy gets its own
 * number out of the project counter (and still does when ten of them race),
 * whether an issue cloned out of a finished state comes back as work to do,
 * whether the sprint promotion that {@code create} performs also fires here, and
 * whether the links, the audit entry and the deliberate silence around copied
 * mentions all land. None of that can be observed against a stubbed service,
 * which is the whole reason this test carries a container.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		// This test writes exactly the rows it reasons about; a seeded workspace
		// would drown them.
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueCloneIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	/** A sprint id is opaque to the creation path — no sprint document is read. */
	private static final String SPRINT_ID = "sprint-1";

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueCloneService clones;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueLinkService links;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private IssueLinkRepository linkRepository;
	@Autowired
	private IssueActivityRepository activities;
	@Autowired
	private IssueCommentRepository comments;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;
	@Autowired
	private NotificationRepository notifications;
	/**
	 * The object store is the one collaborator with no container behind it. Only
	 * the attachment tests reach it, and what they assert is which keys the clone
	 * asks it to copy — not whether MinIO can copy them.
	 */
	@MockitoBean
	private StorageService storage;

	private User owner;
	private User cloner;
	private User mentioned;
	private User outsider;
	private Project project;
	private Issue original;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "issue_links", "issue_activities",
				"issue_comments", "projects", "users", "notifications", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		owner = user("owner");
		cloner = user("cloner");
		mentioned = user("mentioned");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(owner.getId())
				.leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId(), cloner.getId(), mentioned.getId())))
				// A "Backlog" first state is what makes the sprint promotion in
				// create() observable — without one there is nothing to promote from.
				.workflowStates(workflow("Backlog", "Open", "In Progress", "Done"))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		original = issues.create(Issue.builder()
				.projectId(project.getId())
				.title("Kalender & Schichtplanung")
				.description("plain text of the body")
				.descriptionDoc(bodyMentioning(mentioned.getId()))
				.type(Issue.Type.STORY)
				.priority(Issue.Priority.MAJOR)
				.tags(new ArrayList<>(List.of("ui", "planning")))
				.estimateMinutes(180)
				.storyPoints(5)
				.startDate(LocalDate.of(2026, 8, 1))
				.dueDate(LocalDate.of(2026, 8, 20))
				.assigneeIds(new ArrayList<>(List.of(owner.getId())))
				.build(), owner);
		notifications.deleteAll(); // the original's own creation notices are not the subject
		when(storage.copyObject(anyString(), anyString())).thenReturn(true);
	}

	// --- fixtures ------------------------------------------------------------

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).build());
	}

	private static List<Project.WorkflowState> workflow(String... names) {
		List<Project.WorkflowState> states = new ArrayList<>();
		for (String name : names) {
			states.add(Project.WorkflowState.builder().id(name.toLowerCase()).name(name).hue(200).build());
		}
		return states;
	}

	/** A Lexical body carrying one user mention — what makes copied text noisy. */
	private static String bodyMentioning(String userId) {
		return """
				{"root":{"type":"root","children":[{"type":"paragraph","children":[
				{"type":"smartlink","kind":"user","targetId":"%s","text":"@mentioned"}]}]}}"""
				.formatted(userId);
	}

	private IssueCloneService.Options options(boolean links, boolean sprint) {
		return options(false, links, sprint);
	}

	private IssueCloneService.Options options(boolean attachments, boolean links, boolean sprint) {
		return new IssueCloneService.Options("CLONE - " + original.getTitle(),
				List.of(cloner.getId()), attachments, links, sprint);
	}

	private Issue another(String title) {
		return issues.create(Issue.builder().projectId(project.getId()).title(title)
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>()).build(), owner);
	}

	// --- identity ------------------------------------------------------------

	@Test
	void theCloneIsANewIssueWithItsOwnNumber() {
		Issue copy = clones.clone(original.getReadableId(), options(false, false), cloner);

		assertThat(copy.getId()).isNotEqualTo(original.getId());
		assertThat(copy.getReadableId()).isNotEqualTo(original.getReadableId());
		assertThat(copy.getNumberInProject()).isEqualTo(original.getNumberInProject() + 1);
		assertThat(copy.getReadableId()).isEqualTo("HIN-" + copy.getNumberInProject());
	}

	/**
	 * The reason this goes through {@code IssueService.create}: the number comes
	 * from an atomic counter with a collision retry behind it. Ten clones fired at
	 * once is the case a second creation path would quietly get wrong.
	 */
	@Test
	void parallelClonesNeverShareANumber() throws Exception {
		int parallel = 10;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(parallel);
		AtomicReferenceArray<String> keys = new AtomicReferenceArray<>(parallel);
		ExecutorService pool = Executors.newFixedThreadPool(parallel);
		try {
			for (int i = 0; i < parallel; i++) {
				int index = i;
				pool.submit(() -> {
					try {
						start.await();
						keys.set(index, clones.clone(original.getId(),
								new IssueCloneService.Options("CLONE " + index, List.of(), false, false, false),
								cloner).getReadableId());
					}
					catch (Exception ignored) {
						// A failure shows up as a null slot below, which is the assertion.
					}
					finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(60, TimeUnit.SECONDS)).as("all clones finished").isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		List<String> produced = new ArrayList<>();
		for (int i = 0; i < parallel; i++) {
			produced.add(keys.get(i));
		}
		assertThat(produced).doesNotContainNull().doesNotHaveDuplicates().hasSize(parallel);
	}

	@Test
	void theReporterIsWhoeverCloned() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(original.getReporterId()).isEqualTo(owner.getId());
		assertThat(copy.getReporterId()).isEqualTo(cloner.getId());
	}

	// --- what comes along ----------------------------------------------------

	@Test
	void theBodyAndItsFormattingAreCopiedExactly() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getDescription()).isEqualTo(original.getDescription());
		assertThat(copy.getDescriptionDoc()).isEqualTo(original.getDescriptionDoc());
	}

	@Test
	void theClassificationAndThePlanningValuesComeAlong() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getType()).isEqualTo(Issue.Type.STORY);
		assertThat(copy.getPriority()).isEqualTo(Issue.Priority.MAJOR);
		assertThat(copy.getTags()).containsExactlyInAnyOrder("ui", "planning");
		assertThat(copy.getEstimateMinutes()).isEqualTo(180);
		assertThat(copy.getStoryPoints()).isEqualTo(5);
		assertThat(copy.getStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(copy.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
	}

	@Test
	void theAssigneeIsWhoeverTheDialogChose() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getAssigneeIds()).containsExactly(cloner.getId());

		Issue unassigned = clones.clone(original.getId(),
				new IssueCloneService.Options("CLONE - unassigned", List.of(), false, false, false), cloner);

		assertThat(unassigned.getAssigneeIds()).isEmpty();
		assertThat(unassigned.getAssigneeId()).isNull();
	}

	// --- what stays behind ---------------------------------------------------

	/**
	 * Cloning a finished issue must produce work to do. A copy that arrives in
	 * "Done" is the one outcome nobody wants and the easiest to ship by accident,
	 * since every other field is copied verbatim.
	 */
	@Test
	void aFinishedIssueClonesIntoTheFirstState() {
		original.setState("Done");
		original.setResolvedAt(Instant.now());
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getState()).isEqualTo("Backlog");
		assertThat(copy.getResolvedAt()).isNull();
	}

	@Test
	void progressAndHistoryStayWithTheOriginal() {
		original.setSpentMinutes(240);
		original.setWatcherIds(new ArrayList<>(List.of(owner.getId(), mentioned.getId())));
		issueRepository.save(original);
		comments.save(IssueComment.builder().issueId(original.getId()).authorId(owner.getId())
				.text("worth keeping on the original").build());

		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getSpentMinutes()).isZero();
		assertThat(copy.getWatcherIds()).isEmpty();
		assertThat(copy.getAttachments()).isEmpty();
		assertThat(copy.isArchived()).isFalse();
		assertThat(comments.findByIssueIdOrderByCreatedAtAsc(copy.getId()))
				.as("comments belong to the issue they were written on").isEmpty();
		// The only history a copy has is its own creation.
		assertThat(activities.findByIssueIdOrderByCreatedAtDesc(copy.getId()))
				.extracting(IssueActivity::getField)
				.containsExactly(IssueActivity.Field.CREATED);
	}

	@Test
	void anIngestedIssueDoesNotPassItsMailIdentityToTheCopy() {
		original.setInboundMessageId("<mail-1@example.org>");
		original.setInboundSubject("Kalender kaputt");
		original.setReporterEmail("sender@example.org");
		original.setIngestConnectionId("conn-1");
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		// Duplicating the message id would break the dedupe that stops a re-polled
		// mail from becoming a second ticket.
		assertThat(copy.getInboundMessageId()).isNull();
		assertThat(copy.getInboundSubject()).isNull();
		assertThat(copy.getReporterEmail()).isNull();
		assertThat(copy.getIngestConnectionId()).isNull();
	}

	// --- the attachments switch ----------------------------------------------

	/** Hangs [count] files on the original, as an upload would leave them. */
	private List<Issue.Attachment> attach(int count) {
		return attach(count, 2048);
	}

	/** The same, with a size per file — for the budget a clone has to stay inside. */
	private List<Issue.Attachment> attach(int count, long sizeEach) {
		List<Issue.Attachment> files = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			files.add(Issue.Attachment.builder()
					.id("att-" + i)
					.fileName("shot-" + i + ".png")
					.contentType("image/png")
					.size(sizeEach)
					.objectKey("object-" + i)
					.uploaderId(owner.getId())
					.uploadedAt(Instant.now())
					.blurHash("LEHV6nWB2yk8pyo0adR*")
					.build());
		}
		original.setAttachments(files);
		issueRepository.save(original);
		return files;
	}

	@Test
	void withoutTheAttachmentsSwitchTheFilesStayWithTheOriginal() {
		attach(2);

		Issue copy = clones.clone(original.getId(), options(false, false, false), cloner);

		assertThat(copy.getAttachments()).isEmpty();
		// Not even asked for: refusing to copy must not cost a round trip to the
		// object store per file.
		verify(storage, never()).copyObject(anyString(), anyString());
	}

	@Test
	void withTheAttachmentsSwitchEveryFileIsDuplicatedIntoItsOwnObject() {
		List<Issue.Attachment> sources = attach(2);

		Issue copy = clones.clone(original.getId(), options(true, false, false), cloner);

		assertThat(copy.getAttachments()).hasSize(2);
		assertThat(copy.getAttachments()).extracting(Issue.Attachment::getFileName)
				.containsExactly("shot-1.png", "shot-2.png");
		// What a viewer sees is carried; who put it there is whoever cloned, for
		// the same reason the copy's reporter is.
		assertThat(copy.getAttachments()).allSatisfy(file -> {
			assertThat(file.getContentType()).isEqualTo("image/png");
			assertThat(file.getSize()).isEqualTo(2048);
			assertThat(file.getBlurHash()).isEqualTo("LEHV6nWB2yk8pyo0adR*");
			assertThat(file.getUploaderId()).isEqualTo(cloner.getId());
		});
		for (Issue.Attachment source : sources) {
			verify(storage).copyObject(eq(source.getObjectKey()), anyString());
			verify(storage).copyObject(
					eq(ImagePreviewService.attachmentThumbnailKey(source.getId())), anyString());
		}
	}

	/**
	 * The load-bearing property: the copy's files are its own objects. Sharing a
	 * key would make deleting either issue empty the other's attachment grid —
	 * the deletion path removes an attachment's object without asking who else
	 * points at it, because until now nobody could.
	 */
	@Test
	void theCopysFilesAreItsOwnObjectsAndItsOwnIds() {
		List<Issue.Attachment> sources = attach(2);

		Issue copy = clones.clone(original.getId(), options(true, false, false), cloner);

		List<String> sourceKeys = sources.stream().map(Issue.Attachment::getObjectKey).toList();
		List<String> sourceIds = sources.stream().map(Issue.Attachment::getId).toList();
		assertThat(copy.getAttachments()).extracting(Issue.Attachment::getObjectKey)
				.doesNotContainAnyElementsOf(sourceKeys)
				.doesNotHaveDuplicates();
		assertThat(copy.getAttachments()).extracting(Issue.Attachment::getId)
				.doesNotContainAnyElementsOf(sourceIds)
				.doesNotHaveDuplicates();
		// And the original still has everything it had.
		assertThat(issueRepository.findById(original.getId()).orElseThrow().getAttachments())
				.extracting(Issue.Attachment::getObjectKey)
				.containsExactlyElementsOf(sourceKeys);
	}

	/**
	 * A file that will not copy costs the clone that file, not its existence. An
	 * issue that fails to be created because one of its pictures could not be
	 * duplicated is a worse answer than an issue with one picture missing.
	 */
	@Test
	void aFileThatCannotBeCopiedIsLeftOutRatherThanFailingTheClone() {
		attach(2);
		when(storage.copyObject(eq("object-1"), anyString())).thenReturn(false);

		Issue copy = clones.clone(original.getId(), options(true, false, false), cloner);

		assertThat(copy.getAttachments()).singleElement()
				.returns("shot-2.png", Issue.Attachment::getFileName);
	}

	/** A lost thumbnail is a lost preview, which the viewer regenerates on demand. */
	@Test
	void aThumbnailThatCannotBeCopiedDoesNotCostTheAttachment() {
		attach(1);
		when(storage.copyObject(
				eq(ImagePreviewService.attachmentThumbnailKey("att-1")), anyString()))
				.thenReturn(false);

		Issue copy = clones.clone(original.getId(), options(true, false, false), cloner);

		assertThat(copy.getAttachments()).singleElement()
				.returns("shot-1.png", Issue.Attachment::getFileName);
	}

	/**
	 * A thumbnail is only ever written for a picture or a PDF, so an archive or a
	 * Word file has nothing under its thumbnail key — and asking the store to copy
	 * it anyway is a round trip per file answered with a 404, plus a warning line
	 * that reads like a storage fault. The content type already knows.
	 */
	@Test
	void anAttachmentThatCanHaveNoThumbnailIsNotAskedForOne() {
		original.setAttachments(new ArrayList<>(List.of(Issue.Attachment.builder()
				.id("att-zip")
				.fileName("logs.zip")
				.contentType("application/zip")
				.size(4096)
				.objectKey("object-zip")
				.uploaderId(owner.getId())
				.uploadedAt(Instant.now())
				.build())));
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(true, false, false), cloner);

		assertThat(copy.getAttachments()).singleElement()
				.returns("logs.zip", Issue.Attachment::getFileName);
		verify(storage).copyObject(eq("object-zip"), anyString());
		verify(storage, never()).copyObject(
				eq(ImagePreviewService.attachmentThumbnailKey("att-zip")), anyString());
	}

	// --- what one clone is allowed to duplicate ------------------------------

	/**
	 * Uploading costs the caller the file; copying does not. A clone request is a
	 * couple of hundred bytes that tells the store to write whatever hangs off the
	 * original, an issue may carry as many attachments as anybody cares to upload,
	 * and the same request repeats up to the API rate limit every minute — so
	 * without a ceiling the bucket one member can grow per request is bounded by
	 * nothing. Refused before the first copy, not after the fiftieth.
	 */
	@Test
	void anIssueWithMoreFilesThanOneCloneMayCopyIsRefusedBeforeAnythingIsCopied() {
		attach(IssueCloneService.MAX_COPIED_FILES + 1);

		assertThatThrownBy(() -> clones.clone(original.getId(), options(true, false, false), cloner))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.issue.cloneTooManyAttachments");

		verify(storage, never()).copyObject(anyString(), anyString());
		assertThat(issueRepository.count()).as("and no copy was written").isEqualTo(1);
	}

	/**
	 * The same bound in bytes, because fifty files are fifty gigabytes if nothing
	 * says otherwise.
	 */
	@Test
	void filesTotallingMoreThanOneCloneMayCopyAreRefusedBeforeAnythingIsCopied() {
		attach(2, IssueCloneService.MAX_COPIED_BYTES / 2 + 1);

		assertThatThrownBy(() -> clones.clone(original.getId(), options(true, false, false), cloner))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.issue.cloneAttachmentsTooLarge");

		verify(storage, never()).copyObject(anyString(), anyString());
		assertThat(issueRepository.count()).as("and no copy was written").isEqualTo(1);
	}

	/** Cloning without the switch is never refused, however much the original carries. */
	@Test
	void theBudgetOnlyAppliesToACloneThatAsksForTheFiles() {
		attach(IssueCloneService.MAX_COPIED_FILES + 1, IssueCloneService.MAX_COPIED_BYTES);

		Issue copy = clones.clone(original.getId(), options(false, false, false), cloner);

		assertThat(copy.getAttachments()).isEmpty();
	}

	/**
	 * The files are duplicated before the issue that will point at them exists, so
	 * a creation that refuses would otherwise leave objects in the bucket that
	 * nothing ever names again — and nothing reaps: the orphan sweep only knows the
	 * {@code media/} prefix, while an attachment's key is a bare UUID outside it.
	 * A clone whose parent no longer resolves is a request anybody can repeat as
	 * fast as the rate limiter allows, so the copies have to go back with it.
	 */
	@Test
	void objectsCopiedForACloneThatIsNeverWrittenAreRemovedAgain() {
		attach(2);
		original.setParentId("a-parent-that-is-gone");
		issueRepository.save(original);

		assertThatThrownBy(() -> clones.clone(original.getId(), options(true, false, false), cloner))
				.isInstanceOf(ApiException.class);

		assertThat(issueRepository.count()).as("the copy was never written").isEqualTo(1);
		ArgumentCaptor<String> copied = ArgumentCaptor.forClass(String.class);
		verify(storage, times(4)).copyObject(anyString(), copied.capture());
		ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
		verify(storage, times(4)).delete(deleted.capture());
		assertThat(deleted.getAllValues())
				.containsExactlyInAnyOrderElementsOf(copied.getAllValues());
	}

	// --- the sprint checkbox -------------------------------------------------

	@Test
	void withoutTheSprintCheckboxTheCopyStartsInTheBacklog() {
		original.setSprintId(SPRINT_ID);
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(copy.getSprintId()).isNull();
		assertThat(copy.getState()).isEqualTo("Backlog");
	}

	@Test
	void withTheSprintCheckboxTheCopyLandsOnTheBoard() {
		original.setSprintId(SPRINT_ID);
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(false, true), cloner);

		assertThat(copy.getSprintId()).isEqualTo(SPRINT_ID);
		// Promoted out of the backlog state by create(), exactly as a new issue
		// added straight into a sprint would be.
		assertThat(copy.getState()).isEqualTo("Open");
	}

	// --- the links checkbox --------------------------------------------------

	@Test
	void theOriginIsRecordedWhateverTheCheckboxesSay() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(linkRepository.findByTypeAndSourceIdAndTargetId(
				IssueLinkType.CLONES, copy.getId(), original.getId())).isPresent();
	}

	/** One row, two readings — the relationship has to be findable from either end. */
	@Test
	void theOriginIsVisibleFromBothEnds() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(links.linksOf(copy.getId(), cloner))
				.singleElement()
				.satisfies(view -> {
					assertThat(view.type()).isEqualTo(IssueLinkType.CLONES);
					assertThat(view.outward()).isTrue();
					assertThat(view.verb()).isEqualTo("clones");
					assertThat(view.issue().getId()).isEqualTo(original.getId());
				});
		assertThat(links.linksOf(original.getId(), cloner))
				.singleElement()
				.satisfies(view -> {
					assertThat(view.outward()).isFalse();
					assertThat(view.verb()).isEqualTo("is cloned by");
					assertThat(view.issue().getId()).isEqualTo(copy.getId());
				});
	}

	@Test
	void withoutTheLinksCheckboxOnlyTheOriginIsRecorded() {
		Issue blocked = another("Blocked by the original");
		links.addLinks(original.getId(), IssueLinkType.BLOCKS, true, List.of(blocked.getId()), owner);
		original.setDependsOnIds(new ArrayList<>(List.of(blocked.getId())));
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(links.linksOf(copy.getId(), cloner))
				.extracting(IssueLinkService.LinkView::type)
				.containsExactly(IssueLinkType.CLONES);
		assertThat(copy.getDependsOnIds()).isEmpty();
	}

	@Test
	void withTheLinksCheckboxTheOriginalsLinksComeAlongWithTheirDirection() {
		Issue blocked = another("Blocked by the original");
		Issue related = another("Related to the original");
		links.addLinks(original.getId(), IssueLinkType.BLOCKS, true, List.of(blocked.getId()), owner);
		links.addLinks(original.getId(), IssueLinkType.RELATES, true, List.of(related.getId()), owner);
		original.setDependsOnIds(new ArrayList<>(List.of(blocked.getId())));
		issueRepository.save(original);

		Issue copy = clones.clone(original.getId(), options(true, false), cloner);

		assertThat(links.linksOf(copy.getId(), cloner))
				.extracting(IssueLinkService.LinkView::type)
				.containsExactlyInAnyOrder(IssueLinkType.CLONES, IssueLinkType.BLOCKS,
						IssueLinkType.RELATES);
		// The copy stands where the original stood: it blocks, it is not blocked.
		assertThat(links.linksOf(copy.getId(), cloner))
				.filteredOn(view -> view.type() == IssueLinkType.BLOCKS)
				.singleElement()
				.satisfies(view -> assertThat(view.outward()).isTrue());
		assertThat(copy.getDependsOnIds()).containsExactly(blocked.getId());
		// And the original keeps everything it had.
		assertThat(links.linksOf(original.getId(), owner)).hasSize(3);
	}

	/**
	 * Cloning a clone must not inherit the first clone's lineage.
	 *
	 * <p>Every other link type says something about the work and travels with a
	 * copy of it. A clone link says where a ticket came from — history, not a
	 * property — so copying it would have the second copy claim the first one as
	 * its own clone, a relationship that never happened.
	 */
	@Test
	void aCloneOfACloneDoesNotInheritTheFirstOnesLineage() {
		Issue first = clones.clone(original.getId(), options(false, false), cloner);

		Issue second = clones.clone(original.getId(),
				new IssueCloneService.Options("CLONE - second", List.of(), false, true, false), cloner);

		assertThat(links.linksOf(second.getId(), cloner))
				.singleElement()
				.satisfies(view -> {
					assertThat(view.type()).isEqualTo(IssueLinkType.CLONES);
					assertThat(view.outward()).as("the copy clones its own origin").isTrue();
					assertThat(view.issue().getId()).isEqualTo(original.getId());
				});
		// And the first clone is untouched by the second one existing.
		assertThat(links.linksOf(first.getId(), cloner))
				.extracting(view -> view.issue().getId())
				.containsExactly(original.getId());
	}

	/**
	 * A link whose other end this caller cannot see is not copied — creating it
	 * would hand them a relationship they could not have made by hand, and one
	 * they cannot even see afterwards.
	 */
	@Test
	void aLinkToSomethingTheClonerCannotSeeIsNotCopied() {
		Project secret = projects.save(Project.builder().key("SEC").name("Secret")
				.leadId(owner.getId()).leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId())))
				.build());
		Issue hidden = issues.create(Issue.builder().projectId(secret.getId()).title("Hidden")
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>()).build(), owner);
		links.addLinks(original.getId(), IssueLinkType.RELATES, true, List.of(hidden.getId()), owner);

		Issue copy = clones.clone(original.getId(), options(true, false), cloner);

		assertThat(links.linksOf(copy.getId(), cloner))
				.extracting(IssueLinkService.LinkView::type)
				.containsExactly(IssueLinkType.CLONES);
	}

	// --- notifications -------------------------------------------------------

	/**
	 * The copied body still names someone. Nothing about their mention is new, so
	 * telling them again is a notification about a thing that did not happen.
	 */
	@Test
	void mentionsInACopiedBodyNotifyNobody() {
		clones.clone(original.getId(), options(false, false), cloner);

		assertThat(notifications.findAll())
				.extracting(Notification::getUserId)
				.doesNotContain(mentioned.getId());
	}

	/** The new assignee is a real change for them, and still hears about it. */
	@Test
	void theNewAssigneeIsStillNotified() {
		clones.clone(original.getId(),
				new IssueCloneService.Options("CLONE - for the owner", List.of(owner.getId()),
						false, false, false),
				cloner);

		assertThat(notifications.findAll())
				.extracting(Notification::getUserId)
				.contains(owner.getId());
	}

	@Test
	void nobodyIsNotifiedAboutAssigningThemselves() {
		clones.clone(original.getId(), options(false, false), cloner);

		assertThat(notifications.findAll())
				.extracting(Notification::getUserId)
				.doesNotContain(cloner.getId());
	}

	// --- access and audit ----------------------------------------------------

	@Test
	void aNonMemberCannotClone() {
		assertThatThrownBy(() -> clones.clone(original.getId(), options(false, false), outsider))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

		assertThat(issueRepository.count()).as("nothing was created").isEqualTo(1);
	}

	@Test
	void aBlankSummaryIsRefusedBeforeAnythingIsWritten() {
		assertThatThrownBy(() -> clones.clone(original.getId(),
				new IssueCloneService.Options("   ", List.of(), false, false, false), cloner))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.issue.cloneTitleRequired");

		assertThat(issueRepository.count()).isEqualTo(1);
	}

	@Test
	void cloningIsAuditedWithBothEnds() {
		Issue copy = clones.clone(original.getId(), options(false, false), cloner);

		assertThat(auditLog.findAll())
				.filteredOn(entry -> entry.getAction() == AuditAction.ISSUE_CLONED)
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorId()).isEqualTo(cloner.getId());
					assertThat(entry.getMetadata())
							.containsEntry("source", original.getReadableId())
							.containsEntry("clone", copy.getReadableId());
					assertThat(entry.getOutcome()).isEqualTo(AuditLog.Outcome.SUCCESS);
				});
	}
}
