package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueLink;
import com.ahmadre.hinata.issue.IssueLinkType;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.timetracking.ProjectTimeSettings;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Copying a project against a real MongoDB.
 *
 * <p>Two questions decide whether this feature is trustworthy, and neither can be answered against
 * an object built in memory. Does the copy carry the whole plan — sub-tasks to full depth,
 * dependencies and links rewritten onto the copies rather than left pointing at the original? And
 * does it carry <em>nothing</em> of the original's history: no comments, no recorded time, no Git
 * connection, no states, no sprints?
 *
 * <p>The third is what happens when it goes wrong. A half-copied project looks like a plan and is
 * missing the parts nobody checked, so a failure has to leave the database as it found it.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.project-templates.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ProjectCopyIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final LocalDate EVENT = LocalDate.of(2027, 5, 14);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private ProjectCopyService copies;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private IssueCommentRepository comments;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;

	/** The object store has no container behind it; what matters is which keys a copy asks for. */
	@MockitoBean
	private StorageService storage;

	private User lead;
	private User member;
	private User outsider;
	private Project source;
	private Issue epic;
	private Issue task;
	private Issue subtask;
	private Issue blocker;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "issue_links", "issue_comments",
				"issue_activities", "projects", "users", "audit_log", "agile_boards",
				"project_time_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		when(storage.copyObject(anyString(), anyString())).thenReturn(true);
		lead = user("lead");
		member = user("member");
		outsider = user("outsider");
		source = projects.save(Project.builder().key("BFQ").name("Beers 4 Queers")
				.description("The recurring one")
				.color("#F4C2AE")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.workflowStates(workflow("Backlog", "Doing", "Done"))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.labels(new ArrayList<>(List.of(
						Project.Label.builder().id("l1").name("promo").hue(200).build())))
				.eventDate(LocalDate.of(2026, 11, 12))
				.build());

		epic = create("Run the party", Issue.Type.EPIC, null, null);
		task = create("Book the room", Issue.Type.TASK, epic.getId(), weeks(-6));
		subtask = create("Ask the caretaker", Issue.Type.SUBTASK, task.getId(), weeks(-7));
		blocker = create("Pay the deposit", Issue.Type.TASK, epic.getId(), weeks(-5));
		mongo.insert(IssueLink.builder().id("link-1").type(IssueLinkType.BLOCKS)
				.sourceId(blocker.getId()).targetId(task.getId()).createdBy(lead.getId()).build());
		issues.update(task.getId(),
				issue -> issue.setDependsOnIds(new ArrayList<>(List.of(blocker.getId()))), lead);
	}

	// --- the plan travels -----------------------------------------------------

	@Test
	@DisplayName("the copy carries the structure and every issue, sub-tasks included")
	void theWholePlanTravels() {
		ProjectCopyService.Result result = copies.copy(source.getId(), options("Beers 27", "BFQ27"),
				lead);
		Project copy = result.project();

		assertThat(result.issuesCopied()).isEqualTo(4);
		assertThat(result.subtasksCopied()).isEqualTo(3);
		assertThat(copy.getDescription()).isEqualTo("The recurring one");
		assertThat(copy.getColor()).isEqualTo("#F4C2AE");
		assertThat(copy.workflowStateNames()).containsExactly("Backlog", "Doing", "Done");
		assertThat(copy.getResolvedStates()).containsExactly("Done");
		assertThat(copy.labelNames()).containsExactly("promo");
		assertThat(copied(copy)).hasSize(4);
		assertThat(copied(copy)).allSatisfy(issue ->
				assertThat(issue.getReadableId()).startsWith("BFQ27-"));
	}

	@Test
	@DisplayName("the hierarchy points at the copies, never back at the original")
	void theHierarchyIsRewritten() {
		Project copy = copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead).project();

		Issue copiedEpic = byTitle(copy, "Run the party");
		Issue copiedTask = byTitle(copy, "Book the room");
		Issue copiedSub = byTitle(copy, "Ask the caretaker");

		assertThat(copiedTask.getParentId()).isEqualTo(copiedEpic.getId());
		assertThat(copiedSub.getParentId()).isEqualTo(copiedTask.getId());
		// The regression this test exists for: an id left unmapped makes a copy quietly a child
		// of somebody else's ticket.
		assertThat(copied(copy)).noneSatisfy(issue ->
				assertThat(issue.getParentId()).isIn(epic.getId(), task.getId()));
	}

	@Test
	@DisplayName("dependencies and links inside the project are rewritten onto the copies")
	void theInternalLinksAreRewritten() {
		Project copy = copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead).project();

		Issue copiedTask = byTitle(copy, "Book the room");
		Issue copiedBlocker = byTitle(copy, "Pay the deposit");

		assertThat(copiedTask.getDependsOnIds()).containsExactly(copiedBlocker.getId());
		List<IssueLink> links = mongo.find(Query.query(
				Criteria.where("sourceId").is(copiedBlocker.getId())), IssueLink.class);
		assertThat(links).singleElement().satisfies(link -> {
			assertThat(link.getType()).isEqualTo(IssueLinkType.BLOCKS);
			assertThat(link.getTargetId()).isEqualTo(copiedTask.getId());
		});
	}

	@Test
	@DisplayName("a link that leaves the project does not come along")
	void externalLinksAreDropped() {
		Project elsewhere = projects.save(Project.builder().key("OTHER").name("Somewhere else")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId()))).build());
		Issue foreign = issues.create(Issue.builder().projectId(elsewhere.getId())
				.title("Not ours").build(), lead);
		mongo.insert(IssueLink.builder().id("link-2").type(IssueLinkType.RELATES)
				.sourceId(task.getId()).targetId(foreign.getId()).build());

		Project copy = copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead).project();

		Issue copiedTask = byTitle(copy, "Book the room");
		assertThat(mongo.find(Query.query(new Criteria().orOperator(
				Criteria.where("sourceId").is(copiedTask.getId()),
				Criteria.where("targetId").is(copiedTask.getId()))), IssueLink.class))
				.noneSatisfy(link -> assertThat(link.getTargetId()).isEqualTo(foreign.getId()));
	}

	// --- the deadlines --------------------------------------------------------

	@Test
	@DisplayName("the deadlines are recomputed from the copy's own date")
	void theDeadlinesFollowTheNewDate() {
		ProjectCopyService.Result result = copies.copy(source.getId(),
				options("Beers 27", "BFQ27"), lead);

		Issue copiedTask = byTitle(result.project(), "Book the room");
		assertThat(copiedTask.getDueOffset()).isEqualTo(weeks(-6));
		// Six weeks before 14 May 2027, not six weeks before the original's November date.
		assertThat(copiedTask.getDueDate()).isEqualTo(LocalDate.of(2027, 4, 2));
		assertThat(result.deadlinesSet()).isEqualTo(3);
	}

	@Test
	@DisplayName("with no date the rules travel and the deadlines stay empty")
	void aTemplateCopyHasRulesAndNoDates() {
		ProjectCopyService.Options options = new ProjectCopyService.Options(
				"Event template", "TPL", null, true, false, true, false, true);

		Project copy = copies.copy(source.getId(), options, lead).project();

		Issue copiedTask = byTitle(copy, "Book the room");
		assertThat(copy.isTemplate()).isTrue();
		assertThat(copiedTask.getDueOffset()).isEqualTo(weeks(-6));
		assertThat(copiedTask.getDueDate()).isNull();
	}

	// --- the history stays behind ---------------------------------------------

	@Test
	@DisplayName("nothing of the original's history comes along")
	void theHistoryStaysBehind() {
		comments.save(IssueComment.builder().issueId(task.getId()).authorId(lead.getId())
				.text("Called them, they never picked up").createdAt(Instant.now()).build());
		Issue finished = issues.update(blocker.getId(), issue -> {
			issue.setState("Done");
			issue.setWatcherIds(new ArrayList<>(List.of(member.getId())));
			issue.setSpentMinutes(120);
		}, lead);
		assertThat(finished.getResolvedAt()).isNotNull();

		Project copy = copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead).project();

		Issue copiedBlocker = byTitle(copy, "Pay the deposit");
		assertThat(copiedBlocker.getState()).isEqualTo("Backlog");
		assertThat(copiedBlocker.getResolvedAt()).isNull();
		assertThat(copiedBlocker.getSpentMinutes()).isZero();
		assertThat(copiedBlocker.getWatcherIds()).isEmpty();
		assertThat(copiedBlocker.getSprintId()).isNull();
		assertThat(copiedBlocker.getReporterId()).isNull();
		assertThat(comments.findByIssueIdOrderByCreatedAtAsc(copiedBlocker.getId())).isEmpty();
	}

	@Test
	@DisplayName("the Git connection never travels")
	void theGitConnectionStaysBehind() {
		Project connected = projects.findById(source.getId()).orElseThrow();
		connected.setGit(Project.Git.builder().id("g1").provider("github")
				.owner("asta").repo("beers").encryptedToken("secret-token").build());
		projects.save(connected);

		Project copy = copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead).project();

		// A connection carries an encrypted token and a webhook registered for one project.
		// Two projects reacting to one push under one set of credentials is not a copy.
		assertThat(copy.getGit()).isNull();
		assertThat(copy.getExtraRepos()).isEmpty();
	}

	// --- the switches ---------------------------------------------------------

	@Test
	@DisplayName("the board and the time settings come along only when asked")
	void theOptionalPartsAreOptional() {
		mongo.insert(AgileBoard.builder().id("b1").name("Beers board")
				.projectIds(new ArrayList<>(List.of(source.getId())))
				.columns(new ArrayList<>(List.of(AgileBoard.Column.builder().name("Doing")
						.states(new ArrayList<>(List.of("Doing"))).build())))
				.ownerId(lead.getId()).build());
		mongo.insert(ProjectTimeSettings.builder().id("t1").projectId(source.getId())
				.budgetMinutes(4800).build());

		Project without = copies.copy(source.getId(), new ProjectCopyService.Options(
				"No extras", "NOX", EVENT, true, false, false, false, false), lead).project();
		assertThat(boardsOf(without)).isEmpty();
		assertThat(timeSettingsOf(without)).isNull();

		Project with = copies.copy(source.getId(), new ProjectCopyService.Options(
				"With extras", "YEX", EVENT, true, false, true, true, false), lead).project();
		assertThat(boardsOf(with)).singleElement().satisfies(board ->
				assertThat(board.getColumns()).extracting(AgileBoard.Column::getName)
						.containsExactly("Doing"));
		assertThat(timeSettingsOf(with)).isNotNull()
				.satisfies(settings -> assertThat(settings.getBudgetMinutes()).isEqualTo(4800));
	}

	@Test
	@DisplayName("a board shared with another project is left alone")
	void aSharedBoardIsNotTouched() {
		Project other = projects.save(Project.builder().key("OTHER").name("Somewhere else")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId()))).build());
		mongo.insert(AgileBoard.builder().id("shared").name("Both of us")
				.projectIds(new ArrayList<>(List.of(source.getId(), other.getId())))
				.ownerId(lead.getId()).build());

		Project copy = copies.copy(source.getId(), new ProjectCopyService.Options(
				"Beers 27", "BFQ27", EVENT, true, false, true, true, false), lead).project();

		// Adding the copy to it would change what the other project's team sees.
		assertThat(boardsOf(copy)).isEmpty();
		assertThat(mongo.findById("shared", AgileBoard.class).getProjectIds())
				.containsExactly(source.getId(), other.getId());
	}

	@Test
	@DisplayName("members travel only when asked, and whoever copies always leads")
	void membersAreASwitch() {
		Project without = copies.copy(source.getId(), new ProjectCopyService.Options(
				"Solo", "SOLO", EVENT, false, false, true, false, false), member).project();

		assertThat(without.getMemberIds()).containsExactly(member.getId());
		assertThat(without.getLeadIds()).containsExactly(member.getId());

		Project with = copies.copy(source.getId(), options("Team", "TEAM"), member).project();
		assertThat(with.getMemberIds()).contains(lead.getId(), member.getId());
		assertThat(with.getLeadIds()).contains(member.getId(), lead.getId());
	}

	// --- the limits and the refusals -----------------------------------------

	@Test
	@DisplayName("the scope route says what a copy would involve, without copying")
	void scopeCountsWithoutWriting() {
		ProjectCopyService.Scope scope = copies.scopeOf(source.getId(), lead);

		assertThat(scope.issues()).isEqualTo(4);
		assertThat(scope.subtasks()).isEqualTo(3);
		assertThat(scope.withinLimit()).isTrue();
		assertThat(scope.suggestedKey()).isEqualTo("BFQ2");
		assertThat(projects.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a project above the limit is refused, and nothing is written")
	void tooManyIssuesIsRefused() {
		List<Issue> bulk = new ArrayList<>();
		for (int i = 0; i < ProjectCopyService.MAX_ISSUES; i++) {
			bulk.add(Issue.builder().id("bulk-" + i).projectId(source.getId())
					.numberInProject(1000 + i).readableId("BFQ-" + (1000 + i))
					.title("Bulk " + i).state("Backlog").build());
		}
		mongo.insert(bulk, Issue.class);

		assertThatThrownBy(() -> copies.copy(source.getId(), options("Too big", "BIG"), lead))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey()).isEqualTo("error.project.copyTooLarge"));
		assertThat(projects.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("somebody who cannot see the project cannot copy it")
	void anOutsiderIsRefused() {
		assertThatThrownBy(() -> copies.copy(source.getId(), options("Mine now", "MINE"), outsider))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey()).isEqualTo("error.project.notMember"));
		assertThatThrownBy(() -> copies.scopeOf(source.getId(), outsider))
				.isInstanceOf(ApiException.class);
		assertThat(projects.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a key that is taken is refused and leaves nothing behind")
	void aTakenKeyLeavesNothing() {
		long issuesBefore = issueRepository.count();

		assertThatThrownBy(() -> copies.copy(source.getId(), options("Same key", "BFQ"), lead))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey()).isEqualTo("error.project.keyExists"));

		assertThat(projects.count()).isEqualTo(1);
		assertThat(issueRepository.count()).isEqualTo(issuesBefore);
	}

	@Test
	@DisplayName("a failure halfway through takes the half-built project back out")
	void aFailureRollsBack() {
		long projectsBefore = projects.count();
		long issuesBefore = issueRepository.count();
		// The store refuses every copy, and the attachment path logs and carries on — so the
		// failure is forced where it hurts: after the project and its issues exist.
		when(storage.copyObject(anyString(), anyString()))
				.thenThrow(new IllegalStateException("object store is down"));
		Issue withFile = issues.update(task.getId(), issue -> issue.setAttachments(
				new ArrayList<>(List.of(Issue.Attachment.builder().id("a1").fileName("plan.pdf")
						.contentType("application/pdf").size(1024).objectKey("obj-1")
						.uploaderId(lead.getId()).uploadedAt(Instant.now()).build()))), lead);
		assertThat(withFile.getAttachments()).hasSize(1);

		assertThatThrownBy(() -> copies.copy(source.getId(), new ProjectCopyService.Options(
				"Beers 27", "BFQ27", EVENT, true, true, true, true, false), lead))
				.isInstanceOf(RuntimeException.class);

		// Not a single row of the half-built copy survives.
		assertThat(projects.count()).isEqualTo(projectsBefore);
		assertThat(issueRepository.count()).isEqualTo(issuesBefore);
	}

	@Test
	@DisplayName("the copy is audited with both keys and what came along")
	void theCopyIsAudited() {
		copies.copy(source.getId(), options("Beers 27", "BFQ27"), lead);

		assertThat(auditLog.findAll()).anySatisfy(entry -> {
			assertThat(entry.getAction()).isEqualTo(AuditAction.PROJECT_COPIED);
			assertThat(meta(entry, "source")).isEqualTo("BFQ");
			assertThat(meta(entry, "copy")).isEqualTo("BFQ27");
			assertThat(meta(entry, "issues")).isEqualTo("4");
			assertThat(meta(entry, "subtasks")).isEqualTo("3");
		});
	}

	// --- helpers --------------------------------------------------------------

	private ProjectCopyService.Options options(String name, String key) {
		return new ProjectCopyService.Options(name, key, EVENT, true, false, true, false, false);
	}

	private static RelativeDate weeks(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.WEEKS, RelativeDate.Basis.CALENDAR);
	}

	private Issue create(String title, Issue.Type type, String parentId, RelativeDate dueOffset) {
		return issues.create(Issue.builder().projectId(source.getId()).title(title).type(type)
				.parentId(parentId).dueOffset(dueOffset).build(), lead);
	}

	private List<Issue> copied(Project copy) {
		return mongo.find(Query.query(Criteria.where("projectId").is(copy.getId())), Issue.class);
	}

	private Issue byTitle(Project copy, String title) {
		return copied(copy).stream().filter(issue -> title.equals(issue.getTitle())).findFirst()
				.orElseThrow(() -> new AssertionError("no copied issue titled " + title));
	}

	private List<AgileBoard> boardsOf(Project copy) {
		return mongo.find(Query.query(Criteria.where("projectIds").is(copy.getId())),
				AgileBoard.class);
	}

	private ProjectTimeSettings timeSettingsOf(Project copy) {
		return mongo.findOne(Query.query(Criteria.where("projectId").is(copy.getId())),
				ProjectTimeSettings.class);
	}

	private static String meta(AuditLog entry, String key) {
		return entry.getMetadata() == null ? null : entry.getMetadata().get(key);
	}

	private static List<Project.WorkflowState> workflow(String... names) {
		List<Project.WorkflowState> states = new ArrayList<>();
		for (String name : names) {
			states.add(Project.WorkflowState.builder().id(name.toLowerCase()).name(name)
					.hue(200).build());
		}
		return states;
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(Set.of(Role.MEMBER)).build());
	}
}
