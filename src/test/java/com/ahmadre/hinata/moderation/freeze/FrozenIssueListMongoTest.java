package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A frozen issue is absent from the issue list — the query behind the list view,
 * the board, the backlog, the timeline and the MCP {@code search_issues} tool.
 *
 * <p>This is the one the design review called the most important miss, and it was
 * also the most visible: {@code IssueService.search} had no exclusion at all, so a
 * frozen issue's title and readable id appeared on every board card and in every
 * list row in the project. The freeze was working perfectly on the detail view
 * nobody had to open to read the title.
 *
 * <p>Against a real MongoDB rather than a mocked template, for the reason
 * {@code FrozenSearchMongoTest} and {@code SearchAccessMongoTest} both give: what
 * is under test is whether a {@code $nin} composed into a query document actually
 * narrows it, which is a property of the query and not of the code that assembles
 * one. A mocked {@code MongoTemplate} would assert the shape of something nobody
 * executed — and this exclusion sits beside a {@code count} and a {@code find} that
 * share one mutable {@code Query} object, which is exactly the arrangement a mock
 * cannot tell you the truth about.
 *
 * <p><b>The total is asserted as hard as the content.</b> A post-filter would pass
 * a "does not contain" assertion while leaving {@code getTotalElements} counting
 * rows nobody can open — a pager that never reaches its own count, and short pages
 * on the way there. Both are checked below, and the paging test exists because a
 * post-filter is the shape somebody reaches for first.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenIssueListMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String PROJECT = "p-1";

	private final User member = user("u-member", false);
	private final User admin = user("u-admin", true);

	private MongoTemplate mongo;
	private ProjectService projects;
	private IssueRepository issueRepo;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-list");
		mongo.getDb().drop();

		mongo.save(issue("i-frozen", "Frozen title nobody may read", null));
		mongo.save(issue("i-open-1", "Ordinary one", null));
		mongo.save(issue("i-open-2", "Ordinary two", null));
		mongo.save(issue("i-open-3", "Ordinary three", null));

		Project project = Project.builder().id(PROJECT).key("HIN").name("Hinata").archived(false)
				.memberIds(new ArrayList<>(List.of("u-member"))).build();
		projects = mock(ProjectService.class);
		when(projects.visibleTo(any())).thenReturn(List.of(project));
		when(projects.activeProjectIds()).thenReturn(Set.of(PROJECT));
		when(projects.findOptional(PROJECT)).thenReturn(java.util.Optional.of(project));
		issueRepo = mock(IssueRepository.class);
		when(issueRepo.findByParentIdIn(any())).thenReturn(List.of());
	}

	// --- what must not appear ----------------------------------------------------

	@Test
	void aFrozenIssueIsAbsentFromTheListForAMember() {
		assertThat(titles(service(frozenRegistry()), member))
				.doesNotContain("Frozen title nobody may read")
				.contains("Ordinary one");
	}

	/** The account every other filter in this method lets straight through. */
	@Test
	void aFrozenIssueIsAbsentFromTheListForAnAdminToo() {
		assertThat(titles(service(frozenRegistry()), admin))
				.doesNotContain("Frozen title nobody may read")
				.contains("Ordinary one");
	}

	/**
	 * The readable id is as much of a disclosure as the title here: it is what a
	 * board card shows first and what makes the issue openable by hand.
	 */
	@Test
	void searchingByTheFrozenIssuesOwnKeyFindsNothing() {
		Page<Issue> found = service(frozenRegistry()).search(
				params("HIN-I-FROZEN", 0, 25), admin);

		assertThat(found.getContent()).isEmpty();
		assertThat(found.getTotalElements()).isZero();
	}

	/**
	 * The total, separately. A post-filter passes every assertion above and fails
	 * this one — which is precisely why the exclusion is in the query.
	 *
	 * <p><b>Asserted on a NON-FINAL page, and that is the entire test.</b> The obvious
	 * version — page 0 of size 25 over four rows — cannot fail, because
	 * {@code PageImpl}'s constructor silently rewrites the total whenever
	 * {@code offset + pageSize > total}: on a last page it discards the number it was
	 * handed and returns {@code offset + content.size()} instead. So a post-filtered
	 * search that counted all four and returned three would still report three, and
	 * the assertion would pass against the very defect it exists to catch. Four rows
	 * with one frozen, page 0 of size 2, leaves {@code 0 + 2 > total} false for both
	 * the honest total (3) and the inflated one (4), so the number reaching the
	 * assertion is the one the query actually produced.
	 *
	 * <p>This is worth spelling out because the masking is invisible at the call site
	 * and makes a green test mean nothing: it was green for a year against code that
	 * had the exclusion, and it would have stayed green if somebody removed it.
	 */
	@Test
	void theTotalDoesNotCountTheFrozenIssue() {
		Page<Issue> found = service(frozenRegistry()).search(params(null, 0, 2), admin);

		assertThat(found.getTotalElements())
				.as("a page that is not the last one carries the total the query produced, "
						+ "unmasked by PageImpl")
				.isEqualTo(3);
		assertThat(found.getContent()).hasSize(2);
	}

	/**
	 * The same number the other way round: with nothing frozen the total is four, so
	 * the assertion above is measuring the exclusion and not an off-by-one in the
	 * fixture.
	 */
	@Test
	void theTotalOnANonFinalPageCountsEverythingWhenNothingIsFrozen() {
		Page<Issue> found = service(FreezeFixtures.nothingFrozen())
				.search(params(null, 0, 2), admin);

		assertThat(found.getTotalElements()).isEqualTo(4);
	}

	/**
	 * And the pages stay full. A post-filtered page 0 of size 2 returns one row when
	 * a frozen issue happens to sort into it, which the client reads as "the end".
	 */
	@Test
	void everyPageIsFullDespiteTheFrozenRow() {
		IssueService service = service(frozenRegistry());

		assertThat(service.search(params(null, 0, 2), admin).getContent()).hasSize(2);
		assertThat(service.search(params(null, 1, 2), admin).getContent()).hasSize(1);
	}

	// --- what must still work ------------------------------------------------------

	@Test
	void withNothingFrozenTheListIsExactlyWhatItAlwaysWas() {
		Page<Issue> found = service(FreezeFixtures.nothingFrozen()).search(params(null, 0, 25), member);

		assertThat(found.getTotalElements()).isEqualTo(4);
		assertThat(found.getContent()).hasSize(4);
	}

	// --- mention chips -------------------------------------------------------------

	/**
	 * {@code {{issue:KEY}}} renders the referenced issue's title inline wherever the
	 * reference was written, so an unfixed {@code resolveIssues} republishes a frozen
	 * title into every comment that names its key.
	 */
	@Test
	void aFrozenIssueDoesNotResolveAsAMentionChip() {
		IssueService service = service(frozenRegistry());

		assertThat(service.resolveIssues(List.of("HIN-I-FROZEN", "HIN-I-OPEN-1"), admin))
				.extracting(Issue::getId)
				.containsExactly("i-open-1");
	}

	// --- hierarchy ------------------------------------------------------------------

	/**
	 * A frozen sub-task renders under its unfrozen parent, and a frozen ancestor
	 * renders in the breadcrumb — neither guarded by {@code getForUser}, which only
	 * ever saw the issue in the middle.
	 */
	@Test
	void frozenRelativesAreAbsentFromTheHierarchy() {
		mongo.save(issue("i-parent", "Parent", null));
		mongo.save(issue("i-child-frozen", "Frozen child", "i-parent"));
		mongo.save(issue("i-child-open", "Open child", "i-parent"));
		IssueRepository repo = mock(IssueRepository.class);
		when(repo.findById("i-parent"))
				.thenReturn(java.util.Optional.of(mongo.findById("i-parent", Issue.class)));
		when(repo.findByParentId("i-parent")).thenReturn(List.of(
				mongo.findById("i-child-frozen", Issue.class),
				mongo.findById("i-child-open", Issue.class)));
		IssueService service = service(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-child-frozen")), repo);

		assertThat(service.hierarchyOf("i-parent", admin).children())
				.extracting(Issue::getId)
				.containsExactly("i-child-open");
	}

	@Test
	void aFrozenAncestorTruncatesTheBreadcrumbRatherThanAppearingInIt() {
		mongo.save(issue("i-epic-frozen", "Frozen epic", null));
		mongo.save(issue("i-story", "Story", "i-epic-frozen"));
		IssueRepository repo = mock(IssueRepository.class);
		Issue story = mongo.findById("i-story", Issue.class);
		when(repo.findById("i-story")).thenReturn(java.util.Optional.of(story));
		when(repo.findById("i-epic-frozen"))
				.thenReturn(java.util.Optional.of(mongo.findById("i-epic-frozen", Issue.class)));
		when(repo.findByParentId(any())).thenReturn(List.of());
		IssueService service = service(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-epic-frozen")), repo);

		assertThat(service.hierarchyOf("i-story", admin).ancestors()).isEmpty();
	}

	/** The badge has to promise what opening the parent delivers. */
	@Test
	void aFrozenSubtaskIsNotCounted() {
		Issue parent = issue("i-parent", "Parent", null);
		IssueRepository repo = mock(IssueRepository.class);
		when(repo.findByParentIdIn(any())).thenReturn(List.of(
				issue("i-child-frozen", "Frozen child", "i-parent"),
				issue("i-child-open", "Open child", "i-parent")));
		IssueService service = service(FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-child-frozen")), repo);

		service.enrichSubtaskCounts(List.of(parent));

		assertThat(parent.getSubtaskCount()).isEqualTo(1);
	}

	// --- helpers ---------------------------------------------------------------------

	private FrozenContentService frozenRegistry() {
		return FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen"));
	}

	private static IssueService.SearchParams params(String text, int page, int size) {
		return new IssueService.SearchParams(PROJECT, null, null, null, null, null, null, null,
				null, text, false, false, null, null, null, null, "created_asc", page, size);
	}

	private IssueService service(FrozenContentService frozen) {
		return service(frozen, issueRepo);
	}

	private IssueService service(FrozenContentService frozen, IssueRepository repo) {
		return new IssueService(repo, mock(IssueCommentRepository.class),
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

	private static List<String> titles(IssueService service, User who) {
		return service.search(params(null, 0, 25), who).getContent().stream()
				.map(Issue::getTitle).toList();
	}

	private static Issue issue(String id, String title, String parentId) {
		return Issue.builder().id(id).projectId(PROJECT).title(title)
				.readableId("HIN-" + id.toUpperCase(java.util.Locale.ROOT))
				.type(Issue.Type.TASK).parentId(parentId).archived(false)
				.numberInProject(1).createdAt(Instant.now()).updatedAt(Instant.now())
				.attachments(new ArrayList<>()).build();
	}

	private static User user(String id, boolean isAdmin) {
		return User.builder().id(id).displayName(id).active(true)
				.roles(Set.of(isAdmin ? Role.ADMIN : Role.MEMBER)).build();
	}
}
