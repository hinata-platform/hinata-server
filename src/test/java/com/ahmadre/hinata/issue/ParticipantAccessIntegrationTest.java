package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HIN-136 against a real MongoDB: an issue shared with somebody outside its project.
 *
 * <p>The participant reads and comments on exactly that issue and edits nothing of it; the
 * project's members keep every right they had. The rights matrix of the spec, row by row.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ParticipantAccessIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueCloneService cloning;
	@Autowired
	private IssueMoveService moving;
	@Autowired
	private IssueLinkService linking;
	@Autowired
	private TimeTrackingService time;

	private User lead;
	private User member;
	private User participant;
	private User outsider;
	private Project project;
	private Project otherProject;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "issues", "issue_comments",
				"issue_activities", "issue_links", "work_items", "notifications", "audit_log",
				"issue_mail_digests")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		member = user("member");
		participant = user("participant");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("SEC").name("Secret plans")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.workflowStates(new ArrayList<>(List.of(
						Project.WorkflowState.builder().id("s1").name("Open").build(),
						Project.WorkflowState.builder().id("s2").name("Done").build())))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		otherProject = projects.save(Project.builder().key("OTH").name("Other")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId(), participant.getId())))
				.build());
		issue = issue("Get an eSIM", participant.getId());
	}

	// --- reading and commenting ----------------------------------------------

	@Test
	void aParticipantReadsTheIssueCommentsActivityAndHierarchy() {
		issues.addComment(issue.getId(), text("hello"), member);

		assertThat(issues.getForUser(issue.getReadableId(), participant).getId()).isEqualTo(issue.getId());
		assertThat(issues.commentsOf(issue.getId(), 0, 20, participant).getContent()).hasSize(1);
		assertThat(issues.activityOf(issue.getId(), 0, 20, participant)).isNotNull();
		assertThat(issues.hierarchyOf(issue.getId(), participant)).isNotNull();
		assertThat(issues.canAccess(issue, participant)).isTrue();
	}

	@Test
	void aParticipantCommentsRepliesAndReacts() {
		IssueComment root = issues.addComment(issue.getId(), text("Which carrier?"), member);

		IssueComment reply = issues.addComment(issue.getId(), text("Telekom"), root.getId(), participant);
		issues.reactToComment(issue.getId(), root.getId(), "👍", participant);

		assertThat(reply.getAuthorId()).isEqualTo(participant.getId());
		assertThat(issues.commentsOf(issue.getId(), 0, 20, member).getContent()).isNotEmpty();
	}

	@Test
	void anOutsiderStillGets403() {
		assertThatThrownBy(() -> issues.getForUser(issue.getId(), outsider))
				.isInstanceOf(ApiException.class).hasMessageContaining("notMember");
		assertThatThrownBy(() -> issues.addComment(issue.getId(), text("hi"), outsider))
				.isInstanceOf(ApiException.class);
	}

	// --- editing stays with the project -------------------------------------

	@Test
	void aParticipantCannotChangeFieldsArchiveOrDelete() {
		assertReadOnly(() -> issues.update(issue.getId(), i -> i.setTitle("Hijacked"), participant));
		assertReadOnly(() -> issues.setArchived(issue.getId(), true, participant));
		assertReadOnly(() -> issues.delete(issue.getId(), participant));

		Issue stored = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(stored.getTitle()).isEqualTo("Get an eSIM");
		assertThat(stored.isArchived()).isFalse();
	}

	@Test
	void aParticipantCannotMoveTheIssueEvenIntoAProjectTheyBelongTo() {
		assertReadOnly(() -> moving.move(List.of(issue.getId()), otherProject.getId(), Map.of(), false, false,
				participant));

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getProjectId()).isEqualTo(project.getId());
	}

	@Test
	void aParticipantCannotLogTimeClonePinOrLink() {
		Issue other = issue("Another one");
		IssueComment comment = issues.addComment(issue.getId(), text("pin me"), member);

		assertReadOnly(() -> time.create(new TimeTrackingService.NewEntry(null, issue.getId(), 30,
				LocalDate.now(), null, "work", null, null, null, null), com.ahmadre.hinata.timetracking.WorkItem.Source.APP,
				participant));
		assertReadOnly(() -> time.list(issue.getId(), participant));
		assertReadOnly(() -> cloning.clone(issue.getId(),
				new IssueCloneService.Options("Copy", List.of(), false, false, false), participant));
		assertReadOnly(() -> issues.setPinned(issue.getId(), comment.getId(), true, participant));
		assertReadOnly(() -> linking.addLinks(issue.getId(), IssueLinkType.RELATES, true,
				List.of(other.getReadableId()), participant));
	}

	@Test
	void aParticipantMayNotDeleteSomeoneElsesComment() {
		IssueComment comment = issues.addComment(issue.getId(), text("mine"), member);

		assertThatThrownBy(() -> issues.deleteComment(issue.getId(), comment.getId(), participant))
				.isInstanceOf(ApiException.class);
	}

	// --- the detail view ----------------------------------------------------

	@Test
	void aParticipantGetsATrimmedProjectNoSprintsAndNoTime() {
		IssueService.IssueDetail detail = issues.detail(issue.getId(), 30, "newest", 30, participant);

		assertThat(detail.project().getId()).isEqualTo(project.getId());
		assertThat(detail.project().getKey()).isEqualTo("SEC");
		assertThat(detail.project().getWorkflowStates()).hasSize(2);
		assertThat(detail.project().getMemberIds()).isEmpty();
		assertThat(detail.project().getLeadIds()).isEmpty();
		assertThat(detail.project().getLeadId()).isNull();
		assertThat(detail.sprints()).isEmpty();
		assertThat(detail.workItems()).isEmpty();
		assertThat(detail.workItemsTotal()).isZero();
		assertThat(detail.canDelete()).isFalse();
		assertThat(detail.participation().role()).isEqualTo(IssueService.Participation.Role.PARTICIPANT);
		assertThat(detail.participation().canManageParticipants()).isFalse();
	}

	@Test
	void aMemberGetsTheFullProjectAndMayManage() {
		IssueService.IssueDetail detail = issues.detail(issue.getId(), 30, "newest", 30, member);

		assertThat(detail.project().getMemberIds()).contains(member.getId());
		assertThat(detail.participation().role()).isEqualTo(IssueService.Participation.Role.MEMBER);
		assertThat(detail.participation().canManageParticipants()).isTrue();
	}

	@Test
	void detailUsersSayWhoCanSeeTheIssue() {
		issue.setReporterId(outsider.getId());
		issue.setAssigneeIds(new ArrayList<>(List.of(member.getId())));
		issueRepository.save(issue);
		issues.addComment(issue.getId(), text("hi"), participant);

		Map<String, IssueService.DetailUser> byId = issues.detail(issue.getId(), 30, "newest", 30, member)
				.users().stream().collect(Collectors.toMap(IssueService.DetailUser::id, Function.identity()));

		assertThat(byId.get(outsider.getId()).canSeeIssue()).isFalse();
		assertThat(byId.get(member.getId()).canSeeIssue()).isTrue();
		assertThat(byId.get(participant.getId()).canSeeIssue()).isTrue();
	}

	@Test
	void permissionsSayAParticipantCannotDelete() {
		assertThat(issues.permissionsOf(issue.getId(), participant).canDelete()).isFalse();
		assertThat(issues.permissionsOf(issue.getId(), lead).canDelete()).isTrue();
	}

	// --- helpers ------------------------------------------------------------

	private static void assertReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
						.isEqualTo("error.issue.participantReadOnly"));
	}

	private Issue issue(String title, String... participantIds) {
		long number = issueRepository.count() + 1;
		return issueRepository.save(Issue.builder().projectId(project.getId()).title(title)
				.readableId("SEC-" + number).numberInProject(number).state("Open")
				.type(Issue.Type.TASK).reporterId(lead.getId())
				.participantIds(new ArrayList<>(List.of(participantIds)))
				.build());
	}

	private static RichText text(String text) {
		return new RichText(null, text, List.of());
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).build());
	}
}
